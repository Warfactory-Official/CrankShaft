package dev.engine_room.flywheel.impl.visualization;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.task.Plan;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.api.visualization.FramePlanContributor;
import dev.engine_room.flywheel.api.visualization.VisualManager;
import dev.engine_room.flywheel.api.visualization.VisualizationLevel;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDispatcher;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDispatchers;
import dev.engine_room.flywheel.impl.*;
import dev.engine_room.flywheel.impl.extension.LevelExtension;
import dev.engine_room.flywheel.impl.mixin.sodium.RenderSectionManagerAccessor;
import dev.engine_room.flywheel.impl.sodium.TerrainCullGate;
import dev.engine_room.flywheel.impl.task.Flag;
import dev.engine_room.flywheel.impl.task.FlwTaskExecutor;
import dev.engine_room.flywheel.impl.task.TaskExecutorImpl;
import dev.engine_room.flywheel.impl.visual.*;
import dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage;
import dev.engine_room.flywheel.impl.visualization.storage.EffectStorage;
import dev.engine_room.flywheel.impl.visualization.storage.EntityStorage;
import dev.engine_room.flywheel.lib.task.IfElsePlan;
import dev.engine_room.flywheel.lib.task.MapContextPlan;
import dev.engine_room.flywheel.lib.task.NestedPlan;
import dev.engine_room.flywheel.lib.task.SimplePlan;
import dev.engine_room.flywheel.lib.util.LevelAttached;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;

/**
 * A manager class for a single level where visualization is supported.
 */
public class VisualizationManagerImpl implements VisualizationManager {
    private static final LevelAttached<VisualizationManagerImpl> MANAGERS = new LevelAttached<>(
            VisualizationManagerImpl::new, VisualizationManagerImpl::delete);


    private final TaskExecutorImpl taskExecutor;
    private final DistanceUpdateLimiterImpl frameLimiter;
    private final RenderDispatcherImpl renderDispatcher = new RenderDispatcherImpl();
    private final LevelAccessor level;
    private final VisualManagerImpl<BlockEntity, BlockEntityStorage> blockEntities;
    private final VisualManagerImpl<Entity, EntityStorage> entities;
    private final VisualManagerImpl<Effect, EffectStorage> effects;
    private final BerTranslucentCapture berTranslucent = new BerTranslucentCapture();
    private final Flag frameFlag = new Flag("frame");
    private final Flag tickFlag = new Flag("tick");
    // VisualizationManagerImpl can (and should!) be constructed off of the main thread, but it may be
    // difficult for engines to avoid OpenGL calls which would not be safe. Shove all the init logic
    // that depends on engine construction into here, and defer until we get invoked on the main thread.
    @Nullable
    private LateInit lateInit;
    private @Nullable TerrainDispatcher terrainDrawDispatcher;
    @Nullable
    private RenderContext lastFrameCtx;

    private boolean oitDeferred;
    private boolean oitDeferredSodium;
    @Nullable
    private ChunkSectionsToRender deferredChunks;
    @Nullable
    private GpuSampler deferredSampler;
    @Nullable
    private SodiumTerrainOitReplay deferredTerrain;
    @Nullable
    private SodiumWorldRenderer deferredSodiumRenderer;
    @Nullable
    private ChunkRenderMatrices deferredSodiumMatrices;
    private double deferredSodiumX;
    private double deferredSodiumY;
    private double deferredSodiumZ;
    private boolean runningDeferredFallback;

    private VisualizationManagerImpl(LevelAccessor level) {
        this.level = level;
        taskExecutor = FlwTaskExecutor.get();
        frameLimiter = createUpdateLimiter();

        blockEntities = new VisualManagerImpl<>(new BlockEntityStorage());
        entities = new VisualManagerImpl<>(new EntityStorage());
        effects = new VisualManagerImpl<>(new EffectStorage());

        if (level instanceof Level l) {
            LevelExtension.getAllLoadedEntities(l)
                          .forEach(entities::queueAdd);
        }
    }

    public static boolean supportsVisualization(@Nullable LevelAccessor level) {
        if (!BackendManager.isBackendOn() || level == null || !level.isClientSide()) {
            return false;
        }

        if (level instanceof VisualizationLevel flywheelLevel && flywheelLevel.supportsVisualization()) {
            return true;
        }

        return level == Minecraft.getInstance().level;
    }

    @Nullable
    public static VisualizationManagerImpl get(@Nullable LevelAccessor level) {
        if (!supportsVisualization(level)) {
            return null;
        }

        return MANAGERS.get(level);
    }

    public static VisualizationManagerImpl getOrThrow(@Nullable LevelAccessor level) {
        if (!supportsVisualization(level)) {
            throw new IllegalStateException(
                    "Cannot retrieve visualization manager when visualization is not supported by level '" + level + "'!");
        }

        return MANAGERS.get(level);
    }

    // TODO: Consider making these reset actions reuse the existing game objects instead of re-adding them
    //  potentially by keeping the same VisualizationManagerImpl and deleting the engine and visuals but not the game objects
    public static void reset(LevelAccessor level) {
        MANAGERS.remove(level);
    }

    public static void resetAll() {
        MANAGERS.reset();
    }

    private LateInit lateInit() {
        if (lateInit == null) {
            lateInit = new LateInit(level);
        }

        return lateInit;
    }

    private DistanceUpdateLimiterImpl createUpdateLimiter() {
        if (FlwConfig.INSTANCE.limitUpdates()) {
            return new BandedPrimeLimiter();
        } else {
            return new NonLimiter();
        }
    }

    @Override
    public Vec3i renderOrigin() {
        if (lateInit == null) {
            return Vec3i.ZERO;
        } else {
            return lateInit.engine.renderOrigin();
        }
    }

    @Override
    public VisualManager<BlockEntity> blockEntities() {
        return blockEntities;
    }

    @Override
    public VisualManager<Entity> entities() {
        return entities;
    }

    @Override
    public VisualManager<Effect> effects() {
        return effects;
    }

    @Override
    public RenderDispatcher renderDispatcher() {
        return renderDispatcher;
    }

    public BerTranslucentCapture berTranslucent() {
        return berTranslucent;
    }

    /**
     * Begin execution of the tick plan.
     */
    public void tick() {
        // Make sure we're done with any prior frame or tick to avoid racing.
        taskExecutor.syncUntil(frameFlag::isRaised);
        frameFlag.lower();

        taskExecutor.syncUntil(tickFlag::isRaised);
        tickFlag.lower();

        lateInit().tickPlan.execute(taskExecutor, TickableVisualContextImpl.INSTANCE);
    }

    /**
     * Begin execution of the frame plan.
     */
    private void beginFrame(RenderContext context) {
        this.lastFrameCtx = context;

        // Make sure we're done with the last tick.
        // Note we don't lower here because many frames may happen per tick.
        taskExecutor.syncUntil(tickFlag::isRaised);

        frameFlag.lower();

        frameLimiter.tick();

        lateInit().framePlan.execute(taskExecutor, context);
    }

    @Override
    @Nullable
    public RenderContext currentFrameContext() {
        return lastFrameCtx;
    }

    /**
     * Draw all visuals of the given type.
     */
    private void render(RenderContext context) {
        taskExecutor.syncUntil(frameFlag::isRaised);
        lateInit().engine.render(context);
    }

    private boolean renderOit(RenderContext context, @Nullable ChunkSectionsToRender chunks,
                              @Nullable BerTranslucentCapture ber, @Nullable SodiumTerrainOitReplay terrain,
                              @Nullable FabulousCaptures fabulous) {
        Engine engine = lateInit().engine;
        if (engine instanceof EngineImpl impl) {
            return impl.renderOit(context, chunks, ber, terrain, fabulous);
        }
        engine.renderOit(context);
        return false;
    }

    private boolean runOitChain(RenderContext ctx, @Nullable ChunkSectionsToRender chunks,
                                @Nullable SodiumTerrainOitReplay terrain, boolean rotateTerrainRing) {
        FabulousCaptures fabulous = FabulousReroute.capture(ctx);
        boolean composited;
        try {
            composited = renderOit(ctx, chunks, berTranslucent, terrain, fabulous);
        } finally {
            berTranslucent.clear();
            if (rotateTerrainRing && terrainDrawDispatcher != null) {
                terrainDrawDispatcher.endFrame();
            }
        }
        FabulousReroute.onComposited(composited);
        return composited;
    }

    /**
     * Vanilla translucent-seam entry: {@code true} iff the engine composited or the chain deferred.
     */
    public boolean renderTranslucentOit(@Nullable ChunkSectionsToRender chunks, @Nullable GpuSampler sampler) {
        if (runningDeferredFallback) {
            return false;
        }
        RenderContext ctx = lastFrameCtx;
        if (ctx == null) {
            return false;
        }
        if (FabulousLayerTargets.windowOpen()) {
            oitDeferred = true;
            oitDeferredSodium = false;
            deferredChunks = chunks;
            deferredSampler = sampler;
            deferredTerrain = null;
            return true;
        }
        if (!BackendConfig.INSTANCE.terrainMode().compositesTranslucent()) {
            runOitChain(ctx, null, null, false);
            return false;
        }
        return runOitChain(ctx, chunks, null, false);
    }

    /**
     * Sodium translucent-seam entry: {@code true} iff the engine owns and composited the terrain.
     */
    public boolean renderTranslucentOitSodium(SodiumWorldRenderer renderer, ChunkRenderMatrices matrices,
                                              double x, double y, double z, GpuSampler sampler) {
        if (runningDeferredFallback) {
            return false;
        }
        RenderContext ctx = lastFrameCtx;
        if (ctx == null) {
            return false;
        }
        if (FabulousLayerTargets.windowOpen()) {
            oitDeferred = true;
            oitDeferredSodium = true;
            deferredChunks = null;
            deferredTerrain = terrainDrawDispatcher == null ? null : terrainDrawDispatcher.translucentOitReplay();
            deferredSodiumRenderer = renderer;
            deferredSodiumMatrices = matrices;
            deferredSodiumX = x;
            deferredSodiumY = y;
            deferredSodiumZ = z;
            deferredSampler = sampler;
            // Cancel unconditionally: with the window open the transparency post-chain target is null, so
            // Sodium's own draw would NPE.
            return true;
        }
        if (!BackendConfig.INSTANCE.terrainMode().compositesTranslucent()) {
            runOitChain(ctx, null, null, true);
            return false;
        }
        SodiumTerrainOitReplay terrain =
                terrainDrawDispatcher == null ? null : terrainDrawDispatcher.translucentOitReplay();
        boolean engineOwnsTerrain = terrain != null;
        boolean composited = runOitChain(ctx, null, terrain, true);
        return engineOwnsTerrain && composited;
    }

    public void runDeferredTranslucentOit() {
        if (!oitDeferred) {
            return;
        }
        oitDeferred = false;
        boolean sodium = oitDeferredSodium;
        ChunkSectionsToRender chunks = deferredChunks;
        GpuSampler sampler = deferredSampler;
        SodiumTerrainOitReplay terrain = deferredTerrain;
        SodiumWorldRenderer sodiumRenderer = deferredSodiumRenderer;
        ChunkRenderMatrices sodiumMatrices = deferredSodiumMatrices;
        double sodiumX = deferredSodiumX;
        double sodiumY = deferredSodiumY;
        double sodiumZ = deferredSodiumZ;
        deferredChunks = null;
        deferredSampler = null;
        deferredTerrain = null;
        deferredSodiumRenderer = null;
        deferredSodiumMatrices = null;

        RenderContext ctx = lastFrameCtx;
        if (ctx == null) {
            return;
        }
        boolean composited = runOitChain(ctx, chunks, terrain, sodium);
        if (ImplDebugFlags.FABULOUS_LAYER_VIEW) {
            var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            FabulousLayerTargets.debugBlit(main.getColorTextureView(), main.getDepthTextureView());
        }
        // KNOWN CRASH, deliberately unguarded: Sodium's translucent pass target may already be null at this
        // deferred hook, so the redraw below can NPE -- fail fast; frame state is suspect after a chain failure.
        if (!composited) {
            runningDeferredFallback = true;
            try {
                if (!sodium && chunks != null && sampler != null) {
                    chunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, sampler);
                } else if (sodium && terrain != null && sodiumRenderer != null && sodiumMatrices != null && sampler != null) {
                    sodiumRenderer.drawChunkLayer(ChunkSectionLayerGroup.TRANSLUCENT, sodiumMatrices, sodiumX, sodiumY,
                            sodiumZ, sampler);
                }
            } finally {
                runningDeferredFallback = false;
            }
        }
    }

    public boolean renderOpaqueSolidTerrain(ChunkRenderMatrices matrices, RenderSectionManager sectionManager) {
        TerrainMode terrainMode = BackendConfig.INSTANCE.terrainMode();
        boolean opaqueMdi = terrainMode.ownsOpaque();
        // Mode OFF: leave all terrain to Sodium; unpublish so the hooks go
        // inert (else staging rings overflow).
        if (terrainMode == TerrainMode.OFF) {
            if (terrainDrawDispatcher != null) {
                terrainDrawDispatcher.unpublishRegistry();
            }
            return false;
        }
        if (!TerrainDispatchers.isSupported()) {
            TerrainDispatchers.logUnsupportedOnce();
            return false;
        }
        if (terrainDrawDispatcher == null) {
            try {
                terrainDrawDispatcher = TerrainDispatchers.create();
            } catch (RuntimeException e) {
                TerrainDispatchers.disableAfterInitFailure(e);
                return false;
            }
            FlwImpl.LOGGER.info("Flywheel terrain engaged: backend={}, terrainMode={}",
                    BackendManagerImpl.getBackendString(), terrainMode);
        }

        boolean gpuDriven = BackendManagerImpl.isGpuDriven();

        if (opaqueMdi && gpuDriven) {
            terrainDrawDispatcher.publishRegistry();
            // SODIUM_CULL arm: the extract seam cancelled Sodium's render-list build; hand the dispatcher the
            // loaded regions to self-enumerate (same FULL-only/GL-only predicate, so the halves cannot diverge).
            Collection<RenderRegion> selfEnum = TerrainCullGate.shouldCancelSodiumCull()
                    ? ((RenderSectionManagerAccessor) (Object) sectionManager).flywheel$getRegions().getLoadedRegions()
                    : null;
            return terrainDrawDispatcher.drawOpaqueSolid(matrices, sectionManager, selfEnum);
        }

        if (!terrainMode.compositesTranslucent()) {
            terrainDrawDispatcher.unpublishRegistry();
            return false;
        }

        if (gpuDriven) {
            terrainDrawDispatcher.publishRegistry();
            terrainDrawDispatcher.prepareResidentTranslucent(matrices, sectionManager);
        } else {
            terrainDrawDispatcher.unpublishRegistry();
            terrainDrawDispatcher.captureTranslucentArena(matrices, sectionManager);
        }
        return false;
    }

    private void renderCrumbling(RenderContext context,
                                 Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress) {
        if (destructionProgress.isEmpty()) {
            return;
        }

        List<Engine.CrumblingBlock> crumblingBlocks = new ArrayList<>();

        for (var entry : destructionProgress.long2ObjectEntrySet()) {
            var set = entry.getValue();
            if (set == null || set.isEmpty()) {
                // Nothing to do if there's no crumbling.
                continue;
            }

            var visual = blockEntities.getStorage().visualAtPos(entry.getLongKey());

            if (visual == null) {
                // The block doesn't have a visual, this is probably the common case.
                continue;
            }

            List<Instance> instances = new ArrayList<>();

            visual.collectCrumblingInstances(instance -> {
                if (instance != null) {
                    instances.add(instance);
                }
            });

            if (instances.isEmpty()) {
                // The visual doesn't want to render anything crumbling.
                continue;
            }

            var maxDestruction = set.last();

            crumblingBlocks.add(
                    new CrumblingBlockImpl(maxDestruction.getPos(), maxDestruction.getProgress(), instances));
        }

        if (!crumblingBlocks.isEmpty()) {
            lateInit().engine.renderCrumbling(context, crumblingBlocks);
        }
    }

    public void onLightUpdate(SectionPos sectionPos, LightLayer layer) {
        long longPos = sectionPos.asLong();
        lateInit().engine.onLightUpdate(longPos, layer);
        blockEntities.onLightUpdate(longPos);
        entities.onLightUpdate(longPos);
        effects.onLightUpdate(longPos);
    }

    /**
     * True iff any storage holds a {@code LightUpdatedVisual} in this section.
     */
    public boolean isAnyLightUpdatedSection(long sectionPos) {
        return blockEntities.hasLightUpdatedVisualIn(sectionPos)
                || entities.hasLightUpdatedVisualIn(sectionPos)
                || effects.hasLightUpdatedVisualIn(sectionPos);
    }

    /**
     * Free all acquired resources and delete this manager.
     */
    private void delete() {
        // Just finish everything. This may include the work of others but that's okay.
        taskExecutor.syncPoint();

        // Now clean up.
        blockEntities.invalidate();
        entities.invalidate();
        effects.invalidate();
        if (lateInit != null) {
            lateInit.engine.delete();
        }
        if (terrainDrawDispatcher != null) {
            terrainDrawDispatcher.delete();
            terrainDrawDispatcher = null;
        }
        FabulousLayerTargets.delete();
    }

    /**
     * Expose the raw engine, iff it has been initialized and is a default Flywheel engine.
     * <p>For debug information gathering only.
     */
    @Nullable
    public EngineImpl getEngineImpl() {
        if (lateInit == null) {
            return null;
        }
        var engine = lateInit.engine;
        if (engine instanceof EngineImpl engineImpl) {
            return engineImpl;
        }
        return null;
    }

    private record CrumblingBlockImpl(BlockPos pos, int progress,
                                      List<Instance> instances) implements Engine.CrumblingBlock {
    }

    private class LateInit {
        private final Engine engine;

        private final Plan<RenderContext> framePlan;
        private final Plan<TickableVisual.Context> tickPlan;

        private LateInit(LevelAccessor level) {
            engine = BackendManager.currentBackend().createEngine(level);

            var visualizationContext = engine.createVisualizationContext();

            var recreate = NestedPlan.of(
                    blockEntities.getStorage().recreateAllPlan(visualizationContext),
                    entities.getStorage().recreateAllPlan(visualizationContext),
                    effects.getStorage().recreateAllPlan(visualizationContext));

            var update = MapContextPlan.map(this::createVisualFrameContext)
                                       .to(NestedPlan.of(blockEntities.framePlan(visualizationContext),
                                               entities.framePlan(visualizationContext),
                                               effects.framePlan(visualizationContext)));

            Plan<RenderContext> engineFrame = engine.createFramePlan();
            List<Plan<RenderContext>> contributors = FramePlanContributor.all();
            Plan<RenderContext> enginePhase;
            if (contributors.isEmpty()) {
                enginePhase = engineFrame;
            } else {
                @SuppressWarnings("unchecked")
                Plan<RenderContext>[] parallel = new Plan[1 + contributors.size()];
                parallel[0] = engineFrame;
                for (int i = 0; i < contributors.size(); i++) {
                    parallel[i + 1] = contributors.get(i);
                }
                enginePhase = NestedPlan.of(parallel);
            }

            framePlan = IfElsePlan.on((RenderContext ctx) -> engine.updateRenderOrigin(ctx.camera()))
                                  .ifTrue(recreate)
                                  .ifFalse(update)
                                  .plan()
                                  .then(SimplePlan.of(() -> {
                                      if (blockEntities.areGpuLightSectionsDirty() || entities.areGpuLightSectionsDirty() || effects.areGpuLightSectionsDirty()) {
                                          var out = new LongOpenHashSet();
                                          out.addAll(blockEntities.gpuLightSections());
                                          out.addAll(entities.gpuLightSections());
                                          out.addAll(effects.gpuLightSections());
                                          engine.lightSections(out);
                                      }
                                  }))
                                  .then(enginePhase)
                                  // raise + wakeSync in ONE stage: the raising worker unparks the render thread's
                                  // syncUntil park immediately.
                                  .then(SimplePlan.of(() -> {
                                      frameFlag.raise();
                                      taskExecutor.wakeSync();
                                  }));

            tickPlan = NestedPlan.of(blockEntities.tickPlan(visualizationContext),
                                         entities.tickPlan(visualizationContext), effects.tickPlan(visualizationContext))
                                 .then(SimplePlan.of(() -> {
                                     tickFlag.raise();
                                     taskExecutor.wakeSync();
                                 }));
        }

        private DynamicVisual.Context createVisualFrameContext(RenderContext ctx) {
            Vec3i renderOrigin = engine.renderOrigin();
            var cameraPos = ctx.camera().position();

            Matrix4f viewProjection = new Matrix4f(ctx.viewProjection());
            viewProjection.translate((float) (renderOrigin.getX() - cameraPos.x),
                    (float) (renderOrigin.getY() - cameraPos.y), (float) (renderOrigin.getZ() - cameraPos.z));
            FrustumIntersection frustum = new FrustumIntersection(viewProjection);

            return new DynamicVisualContextImpl(ctx.camera(), frustum, ctx.partialTick(), frameLimiter);
        }
    }

    private class RenderDispatcherImpl implements RenderDispatcher {
        @Override
        public void onStartLevelRender(RenderContext ctx) {
            beginFrame(ctx);
        }

        @Override
        public void afterEntities(RenderContext ctx) {
            render(ctx);
        }

        @Override
        public void afterTranslucent(RenderContext ctx) {
            renderOit(ctx, null, null, null, null);
        }

        @Override
        public void beforeCrumbling(RenderContext ctx,
                                    Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress) {
            renderCrumbling(ctx, destructionProgress);
        }
    }
}
