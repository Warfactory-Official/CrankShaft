package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.lwjgl.opengl.*;

final class DeferredCaptureClear implements AutoCloseable {
    private final boolean lights;
    private final int[] bindings;
    private final int[] previous;
    private final String shader;
    private GlProgram program;

    DeferredCaptureClear(boolean lights) {
        this(lights, "layer_clear.comp", lights ? new int[]{0, 1, 2, 5} : new int[]{0, 1, 2});
    }

    DeferredCaptureClear(DeferredOitAdapter adapter) {
        this(adapter.capturesLights(), adapter.clearShader(), adapter.captureBindings());
    }

    private DeferredCaptureClear(boolean lights, String shader, int[] bindings) {
        this.lights = lights;
        this.shader = shader;
        this.bindings = bindings;
        previous = new int[bindings.length];
    }

    void clear(DeferredReplayBuffers buffers, int width, int height) {
        clear(buffers, width, height, 0);
    }

    void clear(DeferredReplayBuffers buffers, int width, int height, long frame) {
        if (program == null) program = DeferredReplayShaders.compute(shader, compilation -> {
            if (!lights) compilation.define("FLW_LAYER_NO_LIGHTS");
        });
        var nativeProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GlStateTracker._setProgram(nativeProgram);
        for (var index = 0; index < bindings.length; index++) {
            var binding = bindings[index];
            previous[index] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, binding);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, buffers.buffer(binding));
        }
        try {
            program.bind();
            program.setVec2("screenSize", width, height);
            program.setInt("flw_oitActive", 1);
            program.setInt("frameCounter", (int) frame);
            GL43.glDispatchCompute((width + 7) / 8, (height + 7) / 8, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        } finally {
            for (var index = 0; index < bindings.length; index++)
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindings[index], previous[index]);
            GlProgram.bind(nativeProgram);
        }
    }

    @Override
    public void close() {
        if (program != null) program.delete();
    }
}
