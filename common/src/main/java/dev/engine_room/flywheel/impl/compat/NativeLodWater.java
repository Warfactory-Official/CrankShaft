package dev.engine_room.flywheel.impl.compat;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitPass;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitShaders;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.impl.far.FarHost;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import org.jspecify.annotations.Nullable;

/**
 * Coordinates native DH/Voxy water with the far renderer's selected transparency graph. Render thread only.
 */
public final class NativeLodWater {
    private static Coordinator coordinator = (_, _) -> false;
    private static @Nullable NativeWaterShaders shaders;
    private static @Nullable RawOitPass pass;
    private static @Nullable NativeWaterRenderer dh;
    private static @Nullable NativeWaterSource voxy;
    private static Host activeHost = Host.NONE;
    private static int nativeDepthFunc;

    private NativeLodWater() {
    }

    /**
     * Installed once by the far renderer; returning true means every required producer and resolve completed.
     */
    public static void register(Coordinator value) {
        coordinator = value;
    }

    /**
     * Records the native DH renderer before its opaque draw, making water preflight available to frame preparation.
     */
    public static void observeDh(NativeWaterRenderer renderer) {
        dh = renderer;
        activeHost = Host.DH;
    }

    /**
     * Records Voxy's native water source after section-renderer construction, before its first frame.
     */
    public static void observeVoxy(NativeWaterSource source) {
        voxy = source;
        activeHost = Host.VOXY;
    }

    /**
     * Requests the selected native DH water group before the far bake queue may publish translucent ownership.
     */
    public static boolean readyDh() {
        return !ShadersModHelper.isShaderPackInUse() && dh != null && dh.flywheel$waterReady();
    }

    /**
     * Requests the selected native Voxy water group before the far bake queue may publish translucent ownership.
     */
    public static boolean readyVoxy() {
        return !ShadersModHelper.isShaderPackInUse() && voxy != null && voxy.flywheel$waterShaders().ready();
    }

    /**
     * Preflight for the host that selected this frame's opaque terrain, before the far bake queue runs.
     */
    public static boolean readyActiveHost() {
        return switch (activeHost) {
            case DH -> readyDh();
            case VOXY -> readyVoxy();
            case NONE -> false;
        };
    }

    public static void releaseDh() {
        dh = null;
        if (activeHost == Host.DH) activeHost = Host.NONE;
    }

    public static void releaseVoxy(NativeWaterSource source) {
        if (voxy == source) {
            voxy = null;
            if (activeHost == Host.VOXY) activeHost = Host.NONE;
        }
    }

    /**
     * Runs before the ordinary water pass; shaderpack hosts retain their own translucent program integration.
     */
    public static boolean render(FarHost target, NativeWaterReplay replay) {
        nativeDepthFunc = GlStateManager.DEPTH.func;
        return !ShadersModHelper.isShaderPackInUse() && coordinator.render(target, replay);
    }

    public static void draw(NativeWaterShaders source, RawOitPass producer, Runnable draw) {
        shaders = source;
        pass = producer;
        var program = source.program(producer);
        program.bind();
        RawOitShaders.bind(program, producer.target());
        draw.run();
        shaders = null;
        pass = null;
        GlStateTracker.invalidateEncoderProgramCache();
    }

    /**
     * Replaces a native program bind only inside an accepted native-water producer callback.
     */
    public static int program(int nativeProgram) {
        return shaders != null && shaders.nativeProgram() == nativeProgram
                ? shaders.program(pass).handle() : nativeProgram;
    }

    /**
     * Remaps DH's cached standalone uniform locations while its original fill routines target a replay program.
     */
    public static int uniform(int nativeProgram, int location) {
        return shaders != null && shaders.nativeProgram() == nativeProgram
                ? shaders.uniform(pass, location) : location;
    }

    /**
     * Reasserts producer attachments/state after the host has set up its draw resources.
     */
    public static void bindProducer() {
        if (pass != null) {
            pass.bind();
            // Native host shader binds bypass GlStateTracker.
            var program = shaders.program(pass);
            GlStateManager._glUseProgram(program.handle());
        }
    }

    /**
     * Preserves the Blaze pipeline's depth comparison and native culling after producer binding. Render thread.
     */
    public static void bindProducerWithNativeCull() {
        bindDhProducer(GlStateManager.DEPTH.func);
    }

    /**
     * DH GL inherits its depth comparison from meta setup, before the shared producer changes it. Render thread.
     */
    public static void bindGlDhProducer() {
        bindDhProducer(nativeDepthFunc);
    }

    private static void bindDhProducer(int depthFunc) {
        if (pass != null) {
            boolean cull = GlStateManager.CULL.enable.enabled;
            bindProducer();
            GlStateManager._depthFunc(depthFunc);
            if (cull) GlStateManager._enableCull();
            else GlStateManager._disableCull();
        }
    }

    private enum Host {NONE, DH, VOXY}

    @FunctionalInterface
    public interface Coordinator {
        boolean render(FarHost target, NativeWaterReplay replay);
    }
}
