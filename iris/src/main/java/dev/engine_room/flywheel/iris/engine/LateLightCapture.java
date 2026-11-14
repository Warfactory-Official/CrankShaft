package dev.engine_room.flywheel.iris.engine;

import dev.engine_room.flywheel.iris.compile.GuestSsbos;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL43C;

/**
 * Render-thread only. Additive guest light follows native material capture; its colour buffer has no intervening
 * non-additive writer.
 */
public final class LateLightCapture {
    private static @Nullable GuestDrawManager pending;
    private static int phase;

    private LateLightCapture() {
    }

    public static int phase() {
        return phase;
    }

    static void schedule(GuestDrawManager manager) {
        pending = manager;
    }

    public static void finish(IrisRenderingPipeline pipeline) {
        var manager = pending;
        pending = null;
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_TEXTURE_FETCH_BARRIER_BIT
                | GL43C.GL_FRAMEBUFFER_BARRIER_BIT);
        if (manager != null) {
            manager.drawLateBillboards();
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT | GL43C.GL_TEXTURE_FETCH_BARRIER_BIT);
        }
        GuestPipelines.prepareLightDemand(pipeline);
        if (manager == null) return;
        phase = 2;
        try {
            manager.drawLateLight();
        } finally {
            phase = 0;
            GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
            GuestSsbos.restore(pipeline);
        }
    }
}
