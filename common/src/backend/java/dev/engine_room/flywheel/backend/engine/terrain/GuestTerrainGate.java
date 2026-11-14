package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.TerrainMode;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Terrain ownership while a shaderpack guest owns the frame, following the configured terrain mode.
 */
public final class GuestTerrainGate {
    public static final int REGION_TIME_BINDING = 9;
    /**
     * The mesh guest uses raster recovery rather than cached per-quad depth records.
     */
    public static final boolean CACHE_RECOVERY = false;
    /**
     * Set with the mesh draw strategy by the guest engine on the render thread, cleared on deletion. The terrain
     * dispatcher still builds its canonical commands; the mesh guest consumes them without CPU geometry repacking.
     */
    public static boolean meshActive;
    /**
     * Reset per opaque terrain call; the mesh producer sets it if either linked program can reject on HiZ.
     */
    public static boolean meshNeedsRecovery;
    /**
     * Render-thread texture published by the mesh terrain draw immediately before Iris sampler setup.
     */
    public static int meshDepthTexture;
    /**
     * Published by Iris at the terrain operation boundary, before native-list selection and command construction.
     */
    public static boolean packActive;
    /**
     * Sodium's truncating integer camera and region-stable fractional translation, published at drawChunkLayer.
     */
    public static int cameraIntX, cameraIntY, cameraIntZ;
    public static float cameraFracX, cameraFracY, cameraFracZ;
    /**
     * Iris re-enters Sodium's terrain draw for its shadow pass, which needs the pack's shadow programs and its own
     * frustum. Installed by the {@code :iris} module; always false without it.
     */
    private static BooleanSupplier shadowPass = () -> false;
    /**
     * Installed by the {@code :iris} module: a pack's own program replaces every native Sodium terrain draw.
     */
    private static BooleanSupplier packInUse = () -> false;
    /**
     * Shadow-map edge length. The cull's sub-pixel rejection is resolution-dependent, so the camera view's size
     * would drop small casters the shadow map does resolve.
     */
    private static IntSupplier shadowResolution = () -> 0;
    /**
     * Installed by the {@code :iris} module: the pack's shadow pass takes reversed depth natively.
     */
    private static BooleanSupplier shadowReversed = () -> false;
    /**
     * Sodium's live per-frame terrain uniforms, republished by the seam that cancels Sodium's own draw. A pack's
     * Sodium-patched terrain program reads both; the slice is ring-allocated, so it is only valid for this frame.
     */
    private static @Nullable GpuBufferSlice globals;
    private static @Nullable GpuBuffer sectionTimeInfo;

    private GuestTerrainGate() {
    }

    public static boolean enabled() {
        BackendConfig config = FlwBackend.config();
        return config != null && config.terrainMode() != TerrainMode.OFF;
    }

    public static boolean meshEnabled() {
        return enabled();
    }

    /**
     * Shadow takeover follows terrain ownership.
     */
    public static boolean shadowEnabled() {
        return enabled();
    }

    public static void setShadowPass(BooleanSupplier predicate) {
        shadowPass = predicate;
    }

    public static void setPackInUse(BooleanSupplier predicate) {
        packInUse = predicate;
    }

    public static boolean packInUse() {
        return packInUse.getAsBoolean();
    }

    public static void setShadowResolution(IntSupplier resolution) {
        shadowResolution = resolution;
    }

    public static int shadowResolution() {
        return shadowResolution.getAsInt();
    }

    public static void setShadowReversed(BooleanSupplier predicate) {
        shadowReversed = predicate;
    }

    public static boolean shadowReversed() {
        return shadowReversed.getAsBoolean();
    }

    public static boolean ownsTerrain() {
        return enabled() && !shadowPass.getAsBoolean();
    }

    public static boolean ownsShadowTerrain() {
        return shadowEnabled() && shadowPass.getAsBoolean();
    }

    public static boolean renderingShadow() {
        return shadowPass.getAsBoolean();
    }

    public static void setSodiumUniforms(GpuBufferSlice globals, GpuBuffer sectionTimeInfo) {
        GuestTerrainGate.globals = globals;
        GuestTerrainGate.sectionTimeInfo = sectionTimeInfo;
    }

    /**
     * Render-thread publication before terrain culling/drawing; valid until the next Sodium layer's publication.
     */
    public static void setSodiumCamera(int x, int y, int z, float fracX, float fracY, float fracZ) {
        cameraIntX = x;
        cameraIntY = y;
        cameraIntZ = z;
        cameraFracX = fracX;
        cameraFracY = fracY;
        cameraFracZ = fracZ;
    }

    public static @Nullable GpuBufferSlice globals() {
        return globals;
    }

    public static @Nullable GpuBuffer sectionTimeInfo() {
        return sectionTimeInfo;
    }
}
