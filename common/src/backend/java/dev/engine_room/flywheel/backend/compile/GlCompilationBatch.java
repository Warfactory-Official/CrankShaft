package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.*;
import java.util.function.IntConsumer;

/**
 * Render-thread compile/link submission with result queries and uniform discovery deferred until the whole batch
 * has been submitted. Driver compiler threads can overlap jobs; no application worker owns the GL context.
 * Shader objects are temporary unless their ownership is transferred to the device shader cache.
 */
public final class GlCompilationBatch implements AutoCloseable {
    private final Map<StageKey, Integer> sharedStages = new HashMap<>();
    private final List<Stage> stages = new ArrayList<>();
    private final List<Program> programs = new ArrayList<>();
    private final Set<Integer> retainedStages = new HashSet<>();
    private final int parallelApi;
    private final int previousThreadHint;
    private int reusedStages;

    public GlCompilationBatch() {
        RenderSystem.assertOnRenderThread();
        GLCapabilities caps = GL.getCapabilities();
        parallelApi = caps.GL_ARB_parallel_shader_compile && caps.glMaxShaderCompilerThreadsARB != MemoryUtil.NULL ? 1
                : caps.GL_KHR_parallel_shader_compile && caps.glMaxShaderCompilerThreadsKHR != MemoryUtil.NULL ? 2 : 0;
        previousThreadHint = parallelApi != 0 ? GL11C.glGetInteger(
                ARBParallelShaderCompile.GL_MAX_SHADER_COMPILER_THREADS_ARB) : 0;
        setThreadHint(-1);
    }

    private void setThreadHint(int count) {
        if (parallelApi == 1) ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(count);
        else if (parallelApi == 2) KHRParallelShaderCompile.glMaxShaderCompilerThreadsKHR(count);
    }

    public int sharedShader(int type, String source, String label) {
        StageKey key = new StageKey(type, source);
        Integer cached = sharedStages.get(key);
        if (cached != null) {
            reusedStages++;
            return cached;
        }
        int shader = shader(type, source, label);
        sharedStages.put(key, shader);
        return shader;
    }

    /**
     * Native device keys already deduplicate stages and must retain distinct ownership for distinct keys.
     */
    public int shader(int type, String source, String label) {
        int shader = GlStateManager.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException("Could not create shader " + label);
        stages.add(new Stage(shader, label));
        GlCompat.safeShaderSource(shader, source);
        GlStateManager.glCompileShader(shader);
        return shader;
    }

    public void retainShader(int shader) {
        retainedStages.add(shader);
    }

    public int link(String label, int[] shaders, String[] attributes, IntConsumer publish) {
        int program = GlStateManager.glCreateProgram();
        if (program == 0) throw new IllegalStateException("Could not create program " + label);
        Program pending = new Program(program, label, shaders, publish);
        programs.add(pending);
        for (int shader : shaders) GlStateManager.glAttachShader(program, shader);
        for (int location = 0; location < attributes.length; location++) {
            if (attributes[location] != null)
                GlStateManager._glBindAttribLocation(program, location, attributes[location]);
        }
        GlStateManager.glLinkProgram(program);
        return program;
    }

    /**
     * Validate every submitted object before transferring ownership or discovering uniforms.
     */
    public void finish(Runnable publishShaders) {
        int pendingAtValidation = 0;
        if (parallelApi != 0) {
            for (Program program : programs) {
                if (GlStateManager.glGetProgrami(program.handle,
                        ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB) == 0) {
                    pendingAtValidation++;
                }
            }
        }
        // These queries may wait, but every compile and link is already queued ahead of them.
        for (Stage stage : stages) {
            if (GlStateManager.glGetShaderi(stage.handle(), GL20C.GL_COMPILE_STATUS) == 0) {
                throw new IllegalStateException("Failed to compile " + stage.label() + ": "
                        + GlStateManager.glGetShaderInfoLog(stage.handle(), 32768));
            }
        }
        for (Program program : programs) {
            int status = GlStateManager.glGetProgrami(program.handle, GL20C.GL_LINK_STATUS);
            String log = GlStateManager.glGetProgramInfoLog(program.handle, 32768);
            if (status == 0 || log.contains("Failed for unknown reason")) {
                throw new IllegalStateException("Failed to link " + program.label + ": " + log);
            }
            if (!log.isEmpty()) FlwBackend.LOGGER.info("Info log when linking {}: {}", program.label, log);
        }
        publishShaders.run();
        for (Program program : programs) {
            program.publish.accept(program.handle);
            program.transferred = true;
        }
        FlwBackend.LOGGER.info(
                "shader batch: {} stages, {} reused stages, {} programs, {} pending at validation, parallel API {}",
                stages.size(), reusedStages, programs.size(), pendingAtValidation, parallelApi);
    }

    @Override
    public void close() {
        for (Program program : programs) {
            for (int shader : program.shaders) GL20C.glDetachShader(program.handle, shader);
            if (!program.transferred) GlStateManager.glDeleteProgram(program.handle);
        }
        for (Stage stage : stages) {
            if (!retainedStages.contains(stage.handle())) GlStateManager.glDeleteShader(stage.handle());
        }
        setThreadHint(previousThreadHint);
    }

    private record StageKey(int type, String source) {
    }

    private record Stage(int handle, String label) {
    }

    private static final class Program {
        private final int handle;
        private final String label;
        private final int[] shaders;
        private final IntConsumer publish;
        private boolean transferred;

        private Program(int handle, String label, int[] shaders, IntConsumer publish) {
            this.handle = handle;
            this.label = label;
            this.shaders = shaders;
            this.publish = publish;
        }
    }
}
