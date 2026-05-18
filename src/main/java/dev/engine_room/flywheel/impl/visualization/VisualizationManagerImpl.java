package dev.engine_room.flywheel.impl.visualization;

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
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.impl.task.Flag;
import dev.engine_room.flywheel.impl.task.FlwTaskExecutor;
import dev.engine_room.flywheel.impl.task.RaisePlan;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.DestroyBlockProgress;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

/**
 * A manager class for a single level where visualization is supported.
 */
public class VisualizationManagerImpl implements VisualizationManager {
    private static final Vec3i ZERO = new Vec3i(0, 0, 0);
    private static final LevelAttached<VisualizationManagerImpl> MANAGERS = new LevelAttached<>(VisualizationManagerImpl::new, VisualizationManagerImpl::delete);


    private final TaskExecutorImpl taskExecutor;
    private final DistanceUpdateLimiterImpl frameLimiter;
    private final RenderDispatcherImpl renderDispatcher = new RenderDispatcherImpl();
    private final World level;

    // VisualizationManagerImpl can (and should!) be constructed off of the main thread, but it may be
    // difficult for engines to avoid OpenGL calls which would not be safe. Shove all the init logic
    // that depends on engine construction into here, and defer until we get invoked on the main thread.
    @Nullable
    private LateInit lateInit;

    private final VisualManagerImpl<TileEntity, BlockEntityStorage> blockEntities;
    private final VisualManagerImpl<Entity, EntityStorage> entities;
    private final VisualManagerImpl<Effect, EffectStorage> effects;

    private final Flag frameFlag = new Flag("frame");
    private final Flag tickFlag = new Flag("tick");

    @Nullable
    private RenderContext lastFrameCtx;

    private VisualizationManagerImpl(World level) {
        this.level = level;
        taskExecutor = FlwTaskExecutor.get();
        frameLimiter = createUpdateLimiter();

        blockEntities = new VisualManagerImpl<>(new BlockEntityStorage());
        entities = new VisualManagerImpl<>(new EntityStorage());
        effects = new VisualManagerImpl<>(new EffectStorage());

        // 1.12.2 gap-fill: upstream re-adds entities/TEs after reset via chunk reload firing
        // their respective load hooks. RenderGlobal.loadRenderers() doesn't reload world chunks,
        // so iterate the world's tracking lists directly to repopulate the fresh manager.
        for (Entity entity : level.loadedEntityList) {
            entities.queueAdd(entity);
        }
        for (TileEntity blockEntity : level.loadedTileEntityList) {
            blockEntities.queueAdd(blockEntity);
        }
    }

    private class LateInit {
        private final Engine engine;

        private final Plan<RenderContext> framePlan;
        private final Plan<TickableVisual.Context> tickPlan;

        private LateInit(World level) {
            engine = BackendManager.currentBackend().createEngine(level);

            var visualizationContext = engine.createVisualizationContext();

            // 1.12.2: origin-snap rebuild — each storage's recreateAllPlan parallelizes its
            // per-visual delete+construct over the ForkJoinPool via CountedCompleter, then
            // serially finalizes (visuals map swap + setup into category lists). Three storages
            // run in parallel via NestedPlan.
            var recreate = NestedPlan.of(
                    blockEntities.getStorage().recreateAllPlan(visualizationContext),
                    entities.getStorage().recreateAllPlan(visualizationContext),
                    effects.getStorage().recreateAllPlan(visualizationContext));

            var update = MapContextPlan.map(this::createVisualFrameContext)
                    .to(NestedPlan.of(blockEntities.framePlan(visualizationContext), entities.framePlan(visualizationContext), effects.framePlan(visualizationContext)));

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

            // 1.12.2: updateRenderOrigin mutates origin + returns true on snap. No re-entrancy
            // guard needed: beginFrame can't fire again until render() unblocks on frameFlag,
            // which is raised at the framePlan tail — strictly after recreate's workers complete.
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
                    .then(RaisePlan.raise(frameFlag));

            tickPlan = NestedPlan.of(blockEntities.tickPlan(visualizationContext), entities.tickPlan(visualizationContext), effects.tickPlan(visualizationContext))
                    .then(RaisePlan.raise(tickFlag));
        }

        private DynamicVisual.Context createVisualFrameContext(RenderContext ctx) {
            Vec3i renderOrigin = engine.renderOrigin();
            Vec3d cameraPos = ctx.camera().getPosition();

            Matrix4f viewProjection = new Matrix4f(ctx.viewProjection());
            viewProjection.translate((float) (renderOrigin.getX() - cameraPos.x), (float) (renderOrigin.getY() - cameraPos.y), (float) (renderOrigin.getZ() - cameraPos.z));
            FrustumIntersection frustum = new FrustumIntersection(viewProjection);

            return new DynamicVisualContextImpl(ctx.camera(), frustum, ctx.partialTick(), frameLimiter);
        }
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

    public static boolean supportsVisualization(@Nullable World level) {
        if (!BackendManager.isBackendOn() || level == null || !level.isRemote) {
            return false;
        }

        if (level instanceof VisualizationLevel flywheelLevel && flywheelLevel.supportsVisualization()) {
            return true;
        }

        // 1.12.2: upstream's `level == Minecraft.getInstance().level` identity check would
        // reject load-time BE registrations because Minecraft.world is still null when
        // World.addTileEntity fires for chunk-load BEs. Accept any WorldClient while mc.world
        // is null; require identity once it's set.
        World mcWorld = Minecraft.getMinecraft().world;
        return level == mcWorld || (mcWorld == null && level instanceof WorldClient);
    }

    @Nullable
    public static VisualizationManagerImpl get(@Nullable World level) {
        if (!supportsVisualization(level)) {
            return null;
        }

        return MANAGERS.get(level);
    }

    public static VisualizationManagerImpl getOrThrow(@Nullable World level) {
        if (!supportsVisualization(level)) {
            throw new IllegalStateException("Cannot retrieve visualization manager when visualization is not supported by level '" + level + "'!");
        }

        return MANAGERS.get(level);
    }

    public static void reset(World level) {
        MANAGERS.remove(level);
    }

    public static void resetAll() {
        MANAGERS.reset();
    }

    @Override
    public Vec3i renderOrigin() {
        if (lateInit == null) {
            return ZERO;
        } else {
            return lateInit.engine.renderOrigin();
        }
    }

    @Override
    public VisualManager<TileEntity> blockEntities() {
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

    private void renderCrumbling(RenderContext context, Long2ObjectMap<SortedSet<DestroyBlockProgress>> destructionProgress) {
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

            crumblingBlocks.add(new CrumblingBlockImpl(maxDestruction.getPosition(), maxDestruction.getPartialBlockDamage(), instances));
        }

        if (!crumblingBlocks.isEmpty()) {
            lateInit().engine.renderCrumbling(context, crumblingBlocks);
        }
    }

    public void onLightUpdate(long sectionPos, EnumSkyBlock layer) {
        lateInit().engine.onLightUpdate(sectionPos, layer);
        blockEntities.onLightUpdate(sectionPos);
        entities.onLightUpdate(sectionPos);
        effects.onLightUpdate(sectionPos);
    }

    /**
     * True iff at least one storage holds a {@code LightUpdatedVisual} registered to this section.
     * Used by the dynamic-lights compats to skip {@link #onLightUpdate} calls for sections with no
     * visuals that would respond to them — avoiding the 3-storage fanout + bake halo expansion
     * for empty sections.
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
        public void beforeCrumbling(RenderContext ctx, Long2ObjectMap<SortedSet<DestroyBlockProgress>> destructionProgress) {
            renderCrumbling(ctx, destructionProgress);
        }
    }

    private record CrumblingBlockImpl(BlockPos pos, int progress,
                                      List<Instance> instances) implements Engine.CrumblingBlock {
    }
}
