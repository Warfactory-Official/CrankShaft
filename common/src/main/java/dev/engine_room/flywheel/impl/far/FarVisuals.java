package dev.engine_room.flywheel.impl.far;

import dev.engine_room.flywheel.backend.engine.far.FarDepth;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.visual.FarVisual;
import dev.engine_room.flywheel.api.visual.Visual;
import dev.engine_room.flywheel.api.visualization.FarVisualRegistry;
import dev.engine_room.flywheel.backend.compile.FarPrograms;
import dev.engine_room.flywheel.backend.engine.far.FarRow;
import dev.engine_room.flywheel.backend.engine.far.FarStore;
import dev.engine_room.flywheel.backend.engine.far.FarStaticMeshes;
import dev.engine_room.flywheel.backend.gl.GlBindlessTable;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.engine.far.FarComputeState;
import dev.engine_room.flywheel.backend.engine.far.SceneOcclusion;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.impl.compat.DhStaticVisuals;
#if MC >= 26.3
import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
#endif
import dev.engine_room.flywheel.impl.compat.LodCompat;
import dev.engine_room.flywheel.impl.compat.VoxyCompat;
import dev.engine_room.flywheel.lib.util.LevelAttached;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.opengl.GL30C;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.impl.compat.NativeLodWater;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.engine.far.FarLayerView;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitChain;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitPass;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitTarget;
import dev.engine_room.flywheel.backend.engine.indirect.MainOitHooks;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import com.mojang.renderpearl.backend.opengl.GlConst;

/**
 * Far geometry for one level, reconstructed from accepted cached terrain or retained when live visuals leave.
 * GPU placements survive visualization resets and join the LoD host's terrain or far draw path. Source workers
 * decode placement data; visual workers queue captures and ends. Model construction and GPU work stay on the
 * render thread.
 */
public final class FarVisuals {
    private static final float NEAR = 0.05f;
    // Multiplies every entry's mesh switch: 0 draws meshes at any size.
    private static final float MESH_SCALE = Float.parseFloat(System.getProperty("crankshaft.far.meshScale", "1"));
    // Reach pruning runs when the camera enters another region of this many blocks.
    private static final int REGION_SHIFT = 6;
    private static final double CHUNK_RADIUS = 8 * Math.sqrt(2);
    private static boolean shaderpackBridge;
    private static @Nullable TranslucentBridge translucentBridge;
    private static final Set<FarVisuals> LIVE = new HashSet<>();
    private static final LevelAttached<FarVisuals> LEVELS = new LevelAttached<>(level -> {
        FarVisuals far = new FarVisuals((Level) level);
        LIVE.add(far);
        return far;
    }, FarVisuals::delete);

    static {
        MainOitHooks.register(new MainOitHooks.Provider() {
            private FarVisuals current() {
                return of(Minecraft.getInstance().level);
            }

            @Override
            public boolean has() {
                return Minecraft.getInstance().level != null && !ShadersModHelper.isShaderPackInUse()
                        && current().hasTranslucent() && current().nearVanilla();
            }

            @Override
            public boolean ready(@Nullable OitInsertMode mode) {
                var far = current();
                return far.nativeLayers.ready(far.store, mode);
            }

            @Override
            public RawOitTarget begin(GpuTextureView color, GpuTextureView depth, int width, int height) {
                var far = current();
                boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
                far.mainLayerView = far.layeredView(far.layeredFrame.projection(), width, height,
                        zeroToOne, true, zeroToOne);
                return new RawOitTarget(((GlTextureView) color).glId(), ((GlTextureView) depth).glId(),
                        width, height, far.layeredFrame.projection(), zeroToOne, true,
                        GL30C.GL_DEPTH_ATTACHMENT, -1);
            }

            @Override
            public void draw(RawOitPass pass) {
                var far = current();
                far.nativeLayers.draw(far.store, far.mainLayerView, pass, LodCompat.vanillaRadius(), true);
            }
        });
        NativeLodWater.register((host, water) -> {
            var level = Minecraft.getInstance().level;
            if (level == null) return false;
            var far = of(level);
            if (!far.hasTranslucent()) return false;
            var target = new RawOitTarget(host.color(), host.depth(), host.width(), host.height(),
                    host.viewToHostClip(), host.zeroToOne(), host.reverseZ(),
                    host.stencil() < 0 ? GL30C.GL_DEPTH_ATTACHMENT : GL30C.GL_DEPTH_STENCIL_ATTACHMENT,
                    host.stencil());
            return far.prepareTranslucentHost(target, water::ready)
                    && far.renderTranslucentHost(target, water::draw);
        });
    }

    private final Level level;
    private final FarTerrainCache terrain;
    private final Map<BlockState, List<FarRow>> terrainRows = new IdentityHashMap<>();
    private final Queue<Change> changes = new ConcurrentLinkedQueue<>();
    // Captures queued but not applied: with storeSize, whether visual creation must end an entry.
    private final AtomicInteger pendingCaptures = new AtomicInteger();
    private volatile int storeSize;
    private final Long2ObjectMap<LongArrayList> chunks = new Long2ObjectOpenHashMap<>();
    private final Long2IntMap droppedLight = new Long2IntOpenHashMap();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f viewToWorld = new Matrix4f();
    private final Matrix4f mainProjection = new Matrix4f();
    private final Matrix4f mainInverseViewProjection = new Matrix4f();
    private final Matrix4f uploadedProjection = new Matrix4f();
    private @Nullable FarStore store;
    private @Nullable FarStaticMeshes staticMeshes;
    private float staticReach;
    private boolean staticMaterialsDirty;
    private int occlusionMode = SceneOcclusion.AUTO;
    private @Nullable FarPrograms storePrograms;
    private @Nullable GpuTexture color;
    private @Nullable GpuTexture depth;
    private @Nullable GpuTexture normal;
    private @Nullable GpuTexture light;
    private @Nullable GpuTexture material;
    private @Nullable FarSurface surface;
    private @Nullable GpuTextureView colorView;
    private @Nullable GpuTextureView depthView;
    private @Nullable ProjectionMatrixBuffer projectionBuffer;
    private @Nullable GpuBufferSlice projectionSlice;
    private @Nullable GpuBuffer noFog;
    private long region = Long.MIN_VALUE;
    private boolean rendered;
    private boolean prepared;
    private boolean nearVanilla;
    private boolean nearMain;
    private double overlapX;
    private double overlapZ;
    private double nearestChunkDistance;
    private boolean mainPending;
    private final FarNativeLayers nativeLayers = new FarNativeLayers();
    private final RawOitChain nativeTranslucent = new RawOitChain();
    private @Nullable RenderContext layeredFrame;
    private @Nullable FarLayerView mainLayerView;
    private Vec3i layeredOrigin = Vec3i.ZERO;
    private boolean translucentHostReady;
    private @Nullable OitInsertMode translucentMode;

    private FarVisuals(Level level) {
        this.level = level;
        terrain = new FarTerrainCache(level, this);
    }

    /**
     * {@code level}'s far visuals, kept until {@link #unload}. Render thread.
     */
    public static FarVisuals of(Level level) {
        return LEVELS.get(level);
    }

    /**
     * Drops {@code level}'s far visuals as the level unloads. Render thread.
     */
    public static void unload(LevelAccessor level) {
        LEVELS.remove(level);
        SceneOcclusion.unload(level);
    }

    /**
     * A LoD renderer is active and both can draw: the GL device with the far programs.
     */
    public static boolean available() {
        return (VoxyCompat.ACTIVE || DistantHorizonsCompat.ACTIVE) && !VkContext.isVulkanHost()
                && FarStore.supported();
    }

    /**
     * Registers the active Iris API's LoD surface bridge during backend registration, on the render thread.
     */
    public static void registerShaderpackBridge() {
        shaderpackBridge = true;
    }

    /**
     * Registers mainline shaderpack producer preflight and frame ownership during client backend setup.
     */
    public static void registerTranslucentBridge(TranslucentBridge bridge) {
        translucentBridge = bridge;
    }

    public interface TranslucentBridge {
        boolean ready(FarStore store);

        void beginFrame(FarStore store, RenderContext frame, Vec3i origin);
    }

    /**
     * Releases every device-lived far resource, before the graphics device closes. Render thread.
     */
    public static void destroyGpuResources() {
        LIVE.forEach(FarVisuals::deleteGpu);
        SceneOcclusion.destroyGpuResources();
        FarComposite.close();
    }

    /**
     * A visual was created for the block entity at {@code pos}: its far entry ends. Visual threads.
     */
    public void visualized(BlockPos pos) {
        if (pendingCaptures.get() + storeSize != 0) {
            changes.add(new Change(pos.asLong(), Vec3i.ZERO, null, 0));
        }
    }

    /**
     * A dropped visual leaves a registered provider representation, its {@link FarVisual} state, or a block model
     * reaching past its block. Any thread.
     */
    public static boolean keeps(Visual visual, BlockState state) {
        return FarVisualRegistry.get(state) != null || visual instanceof FarVisual || VisualStateCapture.oversized(
                state);
    }

    /**
     * Keeps what {@code visual} of the block entity at {@code pos} in {@code state} leaves behind ({@link #keeps}): a
     * registered provider's block-local recipe, otherwise its declared retained representation resolved relative
     * to {@code origin} and any block model reaching past its block. The result is lit at
     * least by {@code light} (block | sky << 16, 0-240 each): rows lit from the light storage
     * ({@code ShaderLightVisual}) and block models carry none of their own. Any thread.
     */
    public void capture(BlockPos pos, BlockState state, Visual visual, Vec3i origin, int light) {
        if (FarVisualRegistry.get(state) != null) {
            pendingCaptures.incrementAndGet();
            changes.add(new Change(pos.asLong(), pos.immutable(), null, light, state));
            return;
        }
        List<FarRow> rows = new ArrayList<>();
        if (visual instanceof FarVisual far) {
            VisualStateCapture.capture(far.retainedState(), state, origin, rows);
        }
        if (VisualStateCapture.oversized(state)) {
            rows.add(VisualStateCapture.block(state, pos.subtract(origin)));
        }
        if (!rows.isEmpty()) {
            pendingCaptures.incrementAndGet();
            changes.add(new Change(pos.asLong(), origin, rows, light));
        }
    }


    /**
     * The chunk holding the visual at {@code pos}, which {@link #keeps} something, is being dropped: its light, gone once the forget packet's light
     * removal runs (possibly before the visual's removal is processed), is kept for its capture. Render thread.
     */
    public void dropping(BlockPos pos) {
        int light = light(pos);
        synchronized (droppedLight) {
            droppedLight.put(pos.asLong(), light);
        }
    }

    /**
     * Makes cached placements eligible again after the chunk leaves live ownership. Render thread.
     */
    public void chunkDropping(ChunkPos chunk) {
        terrain.chunkDropping(chunk);
    }

    /**
     * The light kept by {@link #dropping} for {@code pos}, forgotten, or 0. Any thread.
     */
    public int droppedLight(long pos) {
        synchronized (droppedLight) {
            return droppedLight.remove(pos);
        }
    }

    /**
     * The brightest level light at {@code pos} and its 6 neighbours, as {@link #capture} takes it: the block entity's
     * own block may be opaque. Render thread.
     */
    public int light(BlockPos pos) {
        int block = 0;
        int sky = 0;
        for (BlockPos at : List.of(pos, pos.below(), pos.above(), pos.north(), pos.south(), pos.west(), pos.east())) {
            block = Math.max(block, level.getBrightness(LightLayer.BLOCK, at));
            sky = Math.max(sky, level.getBrightness(LightLayer.SKY, at));
        }
        return block << 4 | sky << 20;
    }

    /**
     * Ends entries whose chunk arrived without a visualizable block entity at their position. Render thread.
     */
    public void chunkLoaded(LevelChunk chunk) {
        apply();
        LongArrayList positions = chunks.get(ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z()));
        if (positions == null) {
            return;
        }
        for (long pos : positions.toLongArray()) {
            BlockEntity blockEntity = chunk.getBlockEntity(BlockPos.of(pos));
            if (blockEntity == null || !VisualizationHelper.canVisualize(blockEntity)) {
                end(pos);
            }
        }
    }

    /**
     * Ends entries past the LoD reach once the camera enters another region, and notes whether an entry may reach
     * into vanilla terrain. Render thread, per tick.
     */
    public void tick(Vec3 camera) {
        apply();
        nearVanilla = false;
        overlapX = camera.x;
        overlapZ = camera.z;
        double nearestSquared = Double.POSITIVE_INFINITY;
        if (store != null) {
            double limit = LodCompat.vanillaRadius() + reach() + CHUNK_RADIUS;
            for (LongIterator it = chunks.keySet().iterator(); it.hasNext() && !nearVanilla; ) {
                long chunk = it.nextLong();
                double dx = SectionPos.sectionToBlockCoord(ChunkPos.getX(chunk), 8) - camera.x;
                double dz = SectionPos.sectionToBlockCoord(ChunkPos.getZ(chunk), 8) - camera.z;
                double distanceSquared = dx * dx + dz * dz;
                nearestSquared = Math.min(nearestSquared, distanceSquared);
                nearVanilla = distanceSquared < limit * limit;
            }
        }
        nearestChunkDistance = Math.sqrt(nearestSquared);
        long current = ChunkPos.pack((int) Math.floor(camera.x) >> REGION_SHIFT,
                (int) Math.floor(camera.z) >> REGION_SHIFT);
        if (current == region || chunks.isEmpty()) {
            return;
        }
        region = current;
        double reach = LodCompat.distance() + 16;
        for (long chunk : chunks.keySet().toLongArray()) {
            double dx = SectionPos.sectionToBlockCoord(ChunkPos.getX(chunk), 8) - camera.x;
            double dz = SectionPos.sectionToBlockCoord(ChunkPos.getZ(chunk), 8) - camera.z;
            if (dx * dx + dz * dz > reach * reach) {
                for (long pos : chunks.get(chunk).toLongArray()) {
                    end(pos);
                }
            }
        }
    }

    /**
     * Prepares entries for {@code frame}, whose visuals are relative to {@code origin}, for the host's late draw.
     * Render thread, outside any render pass, as the frame begins.
     */
    public void render(RenderContext frame, Vec3i origin) {
        apply(frame.camera().position());
        layeredFrame = frame;
        layeredOrigin = origin;
        if (store != null && store.hasLayered()) {
            if (ShadersModHelper.isShaderPackInUse()) {
                boolean ready = translucentBridge != null && translucentBridge.ready(store);
                store.layeredProducerReady(ready);
                if (translucentBridge != null) translucentBridge.beginFrame(store, frame, origin);
            } else {
                var mode = OitConfig.resolveInsertMode();
                boolean ready = nativeLayers.readyForBake(store, mode) & nativeTranslucent.ready(mode)
                        & NativeLodWater.readyActiveHost();
                store.layeredProducerReady(ready);
            }
        }
        if (staticMeshes != null && DistantHorizonsCompat.ACTIVE) {
            DhStaticVisuals.beginFrame(staticMeshes);
        }
        boolean shaderpack = ShadersModHelper.isShaderPackInUse();
        surface = null;
        prepared = false;
        rendered = store != null && store.size() > 0 && available() && (!shaderpack || shaderpackBridge);
        mainPending = false;
        nearMain = false;
        if (!rendered) {
            if (staticMeshes != null && staticMeshes.size() != 0) updateNearMain(frame.camera().position());
            return;
        }
        if (!RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()) {
            throw new IllegalStateException("Far visuals need a zero-to-one depth device");
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        targets(main.width, main.height, shaderpack);
        // Entries past the LoD reach are ended per region; the diagonal of a column of that radius bounds the rest.
        Matrix4f projection = reversedPerspective(frame.projection(), NEAR,
                (float) (LodCompat.distance() * Math.sqrt(2) + 512));
        projection.invert(inverseProjection);
        mainProjection.set(frame.projection());
        frame.viewProjection().invert(mainInverseViewProjection);
        if (projectionBuffer == null) {
            projectionBuffer = new ProjectionMatrixBuffer("flywheel far");
            noFog = noFogBuffer();
        }
        // The matrix overload always uploads; an unchanged far lens can keep its existing GPU slice.
        if (projectionSlice == null || !uploadedProjection.equals(projection)) {
            projectionSlice = projectionBuffer.getBuffer(projection);
            uploadedProjection.set(projection);
        }
        // The frame's own projection, fog and level lighting are installed later in the level render; the host fogs
        // the composited pixels itself.
        GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        GpuBufferSlice savedFog = RenderSystem.getShaderFog();
        GpuBufferSlice savedLights = RenderSystem.getShaderLights();
        RenderSystem.setProjectionMatrix(projectionSlice, ProjectionType.PERSPECTIVE);
        RenderSystem.setShaderFog(noFog.slice());
        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.LEVEL);
        Vec3 camera = frame.camera().position();
        try {
            Matrix4f modelView = new Matrix4f(frame.modelView()).translate((float) (origin.getX() - camera.x),
                    (float) (origin.getY() - camera.y), (float) (origin.getZ() - camera.z));
            store.prepare(colorView, depthView, frame, projection, modelView, origin,
                    level.dimensionType().cardinalLightType() == CardinalLighting.Type.NETHER,
                    (int) level.getGameTime(), frame.partialTick(), MESH_SCALE,
                    shaderpack ? ((GlTexture) normal).glId() : 0,
                    shaderpack ? ((GlTexture) light).glId() : 0,
                    shaderpack ? ((GlTexture) material).glId() : 0);
            frame.modelView().invert(viewToWorld);
            // A completed tick scan bounds the nearest chunk. Motion can only reduce that distance by its length;
            // new captures stay conservative until the next scan, and fresh part bounds may enlarge the reach.
            updateNearMain(camera);
            mainPending = nearMain && !shaderpack;
            prepared = true;
            rendered = false;
        } finally {
            RenderSystem.setShaderLights(savedLights);
            RenderSystem.setShaderFog(savedFog);
            RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType);
        }
    }

    /**
     * Prepared for the main view; its LoD host will supply opaque depth later in the same frame.
     */
    public boolean prepared() {
        return prepared;
    }

    /**
     * Whether this frame's retained geometry may overlap vanilla terrain; consumed by native and pack main draws.
     */
    public boolean nearVanilla() {
        return nearMain;
    }

    /**
     * Called after opaque host terrain, before retained geometry or water has entered its depth target.
     */
    public void afterOpaque(FarDepth opaque) {
        if (rendered) return;
        if (!prepared) {
            var scene = SceneOcclusion.of(level);
            if (scene.requested()) {
                try (var _ = FarComputeState.captureOcclusion()) {
                    scene.capture(opaque);
                }
            }
            return;
        }
        store.draw(opaque, ((GlTexture) color).glId(), ((GlTexture) depth).glId());
        rendered = true;
        if (ShadersModHelper.isShaderPackInUse()) {
            surface = new FarSurface(((GlTexture) color).glId(), ((GlTexture) normal).glId(),
                    ((GlTexture) light).glId(), ((GlTexture) material).glId(), ((GlTexture) depth).glId(),
                    color.getWidth(0), color.getHeight(0), inverseProjection, viewToWorld);
        }
    }

    /**
     * Source for host-specific translucent programs; render thread, borrowed until this level's teardown.
     */
    public @Nullable FarStore layeredStore() {
        return store != null && store.hasLayered() ? store : null;
    }

    public boolean hasTranslucent() {
        return layeredStore() != null && layeredFrame != null;
    }

    /**
     * Origin-relative world to the caller's target. Build once per host view, not per brick.
     */
    public FarLayerView layeredView(Matrix4fc projection, int width, int height, boolean zeroToOne,
                                    boolean reverseZ, boolean hardwareZeroToOne) {
        return layeredView(projection, layeredFrame.modelView(), width, height, zeroToOne, reverseZ,
                hardwareZeroToOne);
    }

    public FarLayerView layeredView(Matrix4fc projection, Matrix4fc modelView, int width, int height,
                                    boolean zeroToOne, boolean reverseZ, boolean hardwareZeroToOne) {
        var camera = layeredFrame.camera().position();
        var matrix = new Matrix4f(projection).mul(modelView)
                                             .translate((float) (layeredOrigin.getX() - camera.x),
                                                     (float) (layeredOrigin.getY() - camera.y),
                                                     (float) (layeredOrigin.getZ() - camera.z));
        return new FarLayerView(matrix, camera, layeredOrigin, width, height, zeroToOne, reverseZ, hardwareZeroToOne);
    }

    /**
     * Checks all native producers before a host may replace its water draw. Admission applies to the next frame.
     */
    public boolean prepareTranslucentHost(RawOitTarget target, BooleanSupplier waterReady) {
        if (!hasTranslucent()) return false;
        var mode = OitConfig.resolveInsertMode();
        boolean ready = nativeLayers.ready(store, mode) & nativeTranslucent.ready(mode) & waterReady.getAsBoolean();
        translucentHostReady = ready;
        translucentMode = mode;
        if (ready) nativeTranslucent.prepare(target.width(), target.height(), mode);
        return ready;
    }

    /**
     * Replays every native water fragment and each far layer into the same selected resolve.
     */
    public boolean renderTranslucentHost(RawOitTarget target, Consumer<RawOitPass> replayWater) {
        if (!hasTranslucent() || !translucentHostReady || translucentMode != OitConfig.resolveInsertMode())
            return false;
        var view = layeredView(target.projection(), target.width(), target.height(), target.zeroToOne(),
                target.reverseZ(), RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        return nativeTranslucent.render(target, translucentMode, pass -> {
            replayWater.accept(pass);
            nativeLayers.draw(store, view, pass, LodCompat.vanillaRadius(), false);
        });
    }

    public void occlusionEnabled(boolean enabled) {
        occlusionMode = enabled ? SceneOcclusion.FORCE_ON : SceneOcclusion.FORCE_OFF;
        if (store != null) store.occlusionEnabled(enabled);
    }

    public void occlusionAuto() {
        occlusionMode = SceneOcclusion.AUTO;
        if (store != null) store.occlusionAuto();
    }

    public FarStore.Submitted submitted() {
        return store == null || store.size() == 0 ? new FarStore.Submitted(0, 0) : store.submitted();
    }

    /**
     * GPU static geometry shared by the active terrain host; null until the first eligible capture.
     */
    public @Nullable FarStaticMeshes staticMeshes() {
        return staticMeshes;
    }

    public int staticInstanceCount() {
        return staticMeshes == null ? 0 : staticMeshes.instanceCount();
    }

    /**
     * Static overhangs inside vanilla range draw against the completed main opaque depth. Render thread.
     */
    public void drawStaticMain(RenderContext frame) {
        if (!nearMain || staticMeshes == null || staticMeshes.size() == 0) return;
        if (DistantHorizonsCompat.ACTIVE) DhStaticVisuals.drawMain(frame);
        #if MC >= 26.3
        if (VoxyCompat.ACTIVE) VoxyStaticVisuals.drawMain(frame);
        #endif
    }

    private float reach() {
        return Math.max(staticReach, store == null ? 0 : store.reach());
    }

    private void updateNearMain(Vec3 camera) {
        nearMain = nearVanilla || nearestChunkDistance <= LodCompat.vanillaRadius() + reach()
                + CHUNK_RADIUS + Math.hypot(camera.x - overlapX, camera.z - overlapZ);
    }

    public FarStore.OcclusionView occlusionView() {
        return store.occlusionView();
    }

    /**
     * Composites this frame's render into {@code host}. Render thread, inside the host's GL work.
     */
    public void draw(FarHost host) {
        if (rendered && surface == null) {
            FarComposite.draw(host, ((GlTexture) color).glId(), ((GlTexture) depth).glId(), inverseProjection);
        }
    }

    /**
     * This frame's unshaded surfaces while a shaderpack is active, consumed by its LoD host on the render thread.
     */
    public @Nullable FarSurface surface() {
        return surface;
    }

    /**
     * Composites this frame's render into the main target where vanilla drew, once a frame and only while an entry
     * may reach into vanilla terrain: hosts show far visuals only where it did not, losing those in front of it.
     * Render thread, after vanilla opaque terrain, while the main depth within the vanilla radius is vanilla's.
     */
    public void drawMain() {
        if (!mainPending || !rendered) {
            return;
        }
        mainPending = false;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        FarComposite.drawMain(((GlTexture) main.getColorTexture()).glId(), ((GlTexture) main.getDepthTexture()).glId(),
                GlConst.toGlInternalId(main.getDepthTexture().getFormat()), main.width, main.height,
                ((GlTexture) color).glId(), ((GlTexture) depth).glId(), inverseProjection,
                mainProjection, mainInverseViewProjection, LodCompat.vanillaRadius());
    }

    public int size() {
        return storeSize + pendingCaptures.get();
    }

    /**
     * Whether this block entity still has a retained snapshot. Render thread, after queued captures are applied.
     */
    public boolean contains(BlockPos pos) {
        long key = pos.asLong();
        return store != null && store.contains(key) || staticMeshes != null && staticMeshes.contains(key);
    }

    /**
     * Kept parts every brick of which is baked, the baked bricks (impostor boxes), and the atlas tiles they share.
     * Render thread.
     */
    public int bakedParts() {
        return (store == null ? 0 : store.bakedParts()) + staticInstanceCount();
    }

    /**
     * Baked parts of an existing retained block entity entry. Render thread.
     */
    public int bakedParts(BlockPos pos) {
        long key = pos.asLong();
        int count = store != null && store.contains(key) ? store.bakedParts(key) : 0;
        var entry = staticMeshes == null ? null : staticMeshes.entry(key);
        if (entry != null) count += entry.instances().size();
        return count;
    }

    /**
     * Baked geometry and appearance counts of an existing retained entry. Render thread.
     */
    public FarStore.EntryStats entryStats(BlockPos pos) {
        long key = pos.asLong();
        var generic = store != null && store.contains(key) ? store.entryStats(key)
                : new FarStore.EntryStats(0, 0, 0, 0);
        var frozen = staticMeshes == null ? null : staticMeshes.entry(key);
        int staticParts = frozen == null ? 0 : frozen.instances().size();
        return new FarStore.EntryStats(generic.parts() + staticParts, generic.bricks() + staticParts,
                generic.tiles(), generic.nativeRows());
    }

    public int bakedBricks() {
        return store == null ? 0 : store.bakedBricks();
    }

    public int tiles() {
        return store == null ? 0 : store.tiles();
    }

    private void apply() {
        apply(null);
    }

    private void apply(@Nullable Vec3 camera) {
        // Reloads also invalidate snapshots whose chunks remain unloaded and produce no new capture events.
        var programs = FarPrograms.get();
        if (storePrograms != programs) {
            translucentHostReady = false;
            nativeLayers.close();
            nativeTranslucent.close();
            if (store != null) store.delete();
            store = null;
            storePrograms = programs;
            if (staticMeshes != null) {
                staticMeshes.delete();
                staticMeshes = null;
                staticReach = 0;
                staticMaterialsDirty = false;
            }
            chunks.clear();
            storeSize = 0;
            terrainRows.clear();
            terrain.restoreResources();
        }
        if (camera != null) terrain.prepare(camera);
        Change change;
        while ((change = changes.poll()) != null) {
            if (change.state != null) {
                var definition = FarVisualRegistry.get(change.state);
                var rows = definition == null ? List.<FarRow>of() : terrainRows(change.state, definition);
                if (rows.isEmpty()) {
                    end(change.pos);
                    pendingCaptures.decrementAndGet();
                    continue;
                }
                change = new Change(change.pos, change.origin, rows, change.light);
            }
            if (change.rows == null) {
                end(change.pos);
                continue;
            }
            if (prepareStore()) {
                boolean existing = store.contains(change.pos)
                        || staticMeshes != null && staticMeshes.contains(change.pos);
                if (!existing) {
                    chunks.computeIfAbsent(chunkOf(change.pos), _ -> new LongArrayList()).add(change.pos);
                    storeSize++;
                }
                var moving = adoptStatic(change);
                if (moving.isEmpty()) store.remove(change.pos);
                else store.add(change.pos, change.origin, moving, change.light);
                nearVanilla = true;
            }
            pendingCaptures.decrementAndGet();
        }
        if (staticMeshes != null) {
            staticMeshes.flush();
            if (staticMaterialsDirty) {
                var textures = Minecraft.getInstance().getTextureManager();
                for (var template : staticMeshes.templates()) textures.getTexture(template.material().texture());
                staticMaterialsDirty = false;
            }
            if (staticMeshes.size() != 0 && GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
                GlBindlessTable.refresh(Minecraft.getInstance().getTextureManager());
            }
        }
    }

    void terrainDefinitionsChanged() {
        terrainRows.clear();
    }

    private List<FarRow> terrainRows(BlockState state, FarVisualRegistry.Definition definition) {
        return terrainRows.computeIfAbsent(state, _ -> {
            var rows = new ArrayList<FarRow>();
            VisualStateCapture.capture(definition.provider().create(state), state, rows);
            return List.copyOf(rows);
        });
    }

    /**
     * Applies an accepted source replacement, retaining unchanged GPU placements. Render thread.
     */
    void replaceTerrainPage(@Nullable FarTerrainPage previous, @Nullable FarTerrainPage next) {
        var nextIndices = new Long2IntOpenHashMap();
        nextIndices.defaultReturnValue(-1);
        if (next != null) {
            for (int i = 0; i < next.positions().length; i++) nextIndices.put(next.positions()[i], i);
        }
        if (previous != null) {
            for (long pos : previous.positions()) {
                if (nextIndices.get(pos) == -1 && !level.isLoaded(BlockPos.of(pos))) end(pos);
            }
        }
        if (next == null) return;
        var previousIndices = new Long2IntOpenHashMap();
        previousIndices.defaultReturnValue(-1);
        if (previous != null) {
            for (int i = 0; i < previous.positions().length; i++) previousIndices.put(previous.positions()[i], i);
        }
        for (int i = 0; i < next.positions().length; i++) {
            long position = next.positions()[i];
            var pos = BlockPos.of(position);
            if (level.isLoaded(pos)) continue;
            int old = previousIndices.get(position);
            if (old != -1 && previous.states()[old] == next.states()[i]
                    && previous.lights()[old] == next.lights()[i] && contains(pos)) continue;
            var state = next.states()[i];
            var definition = FarVisualRegistry.get(state);
            if (definition == null) continue;
            var rows = terrainRows(state, definition);
            if (rows.isEmpty()) {
                end(position);
            } else {
                pendingCaptures.incrementAndGet();
                changes.add(new Change(position, pos, rows, next.lights()[i]));
            }
        }
    }

    private List<FarRow> adoptStatic(Change change) {
        boolean host = VoxyCompat.staticVisualsAvailable()
                || DistantHorizonsCompat.ACTIVE && DhStaticVisuals.available();
        if (!host) {
            removeStatic(change.pos);
            return change.rows;
        }
        var frozen = new ArrayList<FarRow>();
        var moving = new ArrayList<FarRow>();
        for (var row : change.rows) {
            if (FarStaticMeshes.eligible(row)) frozen.add(row);
            else moving.add(row);
        }
        if (frozen.isEmpty()) {
            removeStatic(change.pos);
            return change.rows;
        }
        if (staticMeshes == null) {
            staticMeshes = new FarStaticMeshes((before, after) -> {
                if (DistantHorizonsCompat.ACTIVE) DhStaticVisuals.changed(staticMeshes, before, after);
            });
        }
        if (!staticMeshes.add(change.pos, change.origin, frozen, change.light)) {
            removeStatic(change.pos);
            return change.rows;
        }
        staticMaterialsDirty = true;
        var bounds = staticMeshes.entry(change.pos).bounds();
        double x = BlockPos.getX(change.pos) + 0.5;
        double z = BlockPos.getZ(change.pos) + 0.5;
        double dx = Math.max(Math.abs(bounds.minX() - x), Math.abs(bounds.maxX() - x));
        double dz = Math.max(Math.abs(bounds.minZ() - z), Math.abs(bounds.maxZ() - z));
        staticReach = Math.max(staticReach, (float) Math.hypot(dx, dz));
        return moving;
    }

    private void removeStatic(long pos) {
        if (staticMeshes == null) return;
        staticMeshes.remove(pos);
        if (staticMeshes.size() == 0) staticReach = 0;
    }

    private boolean prepareStore() {
        if (!available()) {
            return false;
        }
        FarPrograms programs = FarPrograms.get();
        if (store == null) {
            store = new FarStore();
            storePrograms = programs;
            if (occlusionMode != SceneOcclusion.AUTO) {
                store.occlusionEnabled(occlusionMode == SceneOcclusion.FORCE_ON);
            }
        }
        return true;
    }

    private void end(long pos) {
        boolean dynamic = store != null && store.contains(pos);
        boolean frozen = staticMeshes != null && staticMeshes.contains(pos);
        if (!dynamic && !frozen) {
            return;
        }
        if (dynamic) store.remove(pos);
        if (frozen) removeStatic(pos);
        storeSize--;
        long chunk = chunkOf(pos);
        LongArrayList positions = chunks.get(chunk);
        positions.rem(pos);
        if (positions.isEmpty()) {
            chunks.remove(chunk);
        }
    }

    private static long chunkOf(long pos) {
        return ChunkPos.pack(SectionPos.blockToSectionCoord(BlockPos.getX(pos)),
                SectionPos.blockToSectionCoord(BlockPos.getZ(pos)));
    }

    private void delete() {
        terrain.close();
        terrainRows.clear();
        changes.clear();
        chunks.clear();
        synchronized (droppedLight) {
            droppedLight.clear();
        }
        deleteGpu();
        LIVE.remove(this);
    }

    private void deleteGpu() {
        nativeLayers.close();
        nativeTranslucent.close();
        translucentHostReady = false;
        layeredFrame = null;
        terrainRows.clear();
        terrain.restoreResources();
        rendered = false;
        prepared = false;
        surface = null;
        mainPending = false;
        nearMain = false;
        chunks.clear();
        storeSize = 0;
        if (staticMeshes != null) {
            staticMeshes.delete();
            staticMeshes = null;
            staticReach = 0;
            staticMaterialsDirty = false;
        }
        if (store != null) {
            store.delete();
            store = null;
            storeSize = 0;
        }
        if (colorView != null) {
            colorView.close();
            depthView.close();
            color.close();
            depth.close();
            colorView = depthView = null;
            color = depth = null;
        }
        closeSurfaceTargets();
        if (projectionBuffer != null) {
            projectionBuffer.close();
            noFog.close();
            projectionBuffer = null;
            projectionSlice = null;
            noFog = null;
        }
    }

    private void targets(int width, int height, boolean shaderpack) {
        if (color != null && color.getWidth(0) == width && color.getHeight(0) == height) {
            if (shaderpack && normal == null) {
                surfaceTargets(width, height);
            }
            return;
        }
        if (color != null) {
            colorView.close();
            depthView.close();
            color.close();
            depth.close();
        }
        closeSurfaceTargets();
        var device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
        color = device.createTexture("flywheel:far_color", usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        depth = device.createTexture("flywheel:far_depth", usage, GpuFormat.D32_FLOAT, width, height, 1, 1);
        colorView = device.createTextureView(color);
        depthView = device.createTextureView(depth);
        if (shaderpack) {
            surfaceTargets(width, height);
        }
    }

    private void surfaceTargets(int width, int height) {
        var device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        normal = device.createTexture("flywheel:far_normal", usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        light = device.createTexture("flywheel:far_light", usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        material = device.createTexture("flywheel:far_material", usage, GpuFormat.RGBA32_UINT, width, height, 1, 1);
    }

    private void closeSurfaceTargets() {
        if (normal != null) {
            normal.close();
            light.close();
            material.close();
            normal = light = material = null;
        }
    }

    // The main projection's lens with reversed-Z zero-to-one depth over [near, far].
    private static Matrix4f reversedPerspective(Matrix4fc main, float near, float far) {
        Matrix4f projection = new Matrix4f(main);
        projection.m22(near / (far - near));
        projection.m32(far * near / (far - near));
        return projection;
    }

    // FogRenderer's disabled fog: clear colour, every distance at Float.MAX_VALUE.
    private static GpuBuffer noFogBuffer() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, FogRenderer.FOG_UBO_SIZE).putVec4(0, 0, 0, 0);
            for (int i = 0; i < 6; i++) {
                builder.putFloat(Float.MAX_VALUE);
            }
            return RenderSystem.getDevice().createBuffer(() -> "flywheel far fog", GpuBuffer.USAGE_UNIFORM,
                    builder.get());
        }
    }

    // A capture (rows) or the end of the entry at pos (no rows).
    private record Change(long pos, Vec3i origin, @Nullable List<FarRow> rows, int light,
                          @Nullable BlockState state) {
        private Change(long pos, Vec3i origin, @Nullable List<FarRow> rows, int light) {
            this(pos, origin, rows, light, null);
        }
    }
}
