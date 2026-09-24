package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.TerrainMode;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Opt-in for drawing terrain while a shaderpack guest owns the frame. Off by default: engaging it moves every Iris
 * baseline, since terrain stops being Iris's own draw.
 */
public final class GuestTerrainGate {
    private static final boolean MESH_PROPERTY = Boolean.getBoolean("crankshaft.iris.mesh");
    private static final boolean SHADOW_OFF = Boolean.getBoolean("crankshaft.iris.terrainShadow.off");
    /**
     * Startup-only: trade additional per-quad storage for cached current-depth recovery on the mesh guest.
     */
    public static final boolean CACHE_RECOVERY = MESH_PROPERTY && Boolean.getBoolean(
            "crankshaft.iris.mesh.cacheRecovery");

    public static boolean enabled() {
        BackendConfig config = FlwBackend.config();
        return config != null && config.terrainMode() != TerrainMode.OFF;
    }

    public static boolean meshEnabled() {
        return enabled() && MESH_PROPERTY;
    }

    /**
     * Independently disable shadow takeover while keeping the main terrain guest.
     */
    public static boolean shadowEnabled() {
        return enabled() && !SHADOW_OFF;
    }
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
     * Shadow-map edge length. The cull's sub-pixel rejection is resolution-dependent, so the camera view's size
     * would drop small casters the shadow map does resolve.
     */
    private static IntSupplier shadowResolution = () -> 0;

    /**
     * Sodium's live per-frame terrain uniforms, republished by the seam that cancels Sodium's own draw. A pack's
     * Sodium-patched terrain program reads both; the slice is ring-allocated, so it is only valid for this frame.
     */
    private static @Nullable GpuBufferSlice globals;
    private static @Nullable GpuBuffer sectionTimeInfo;

    private GuestTerrainGate() {
    }

    public static void setShadowPass(BooleanSupplier predicate) {
        shadowPass = predicate;
    }

    public static void setShadowResolution(IntSupplier resolution) {
        shadowResolution = resolution;
    }

    public static int shadowResolution() {
        return shadowResolution.getAsInt();
    }

    public static boolean ownsTerrain() {
        return enabled() && !shadowPass.getAsBoolean();
    }

    public static boolean ownsShadowTerrain() {
        return shadowEnabled() && shadowPass.getAsBoolean();
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
