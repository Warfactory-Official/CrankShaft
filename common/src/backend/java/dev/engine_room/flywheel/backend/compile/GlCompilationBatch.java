package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.*;
import java.util.function.IntConsumer;

/** Render-thread only; unretained shaders/unpublished programs deleted on close. */
public final class GlCompilationBatch implements AutoCloseable {
    private final Map<StageKey, Integer> sharedStages = new HashMap<>();
    private final List<Stage> stages = new ArrayList<>();
    private final Map<Integer, Stage> stageHandles = new HashMap<>();
    private final List<Program> programs = new ArrayList<>();
    private final Set<Integer> retainedStages = new HashSet<>();
    private final int parallelApi;
    private boolean hinted;
    private int previousThreadHint;
    private int reusedStages;
    private final int rejectedBefore = GlProgramBinaryCache.rejected;
    private final long storedBefore = GlProgramBinaryCache.storedBytes;

    public GlCompilationBatch() {
        RenderSystem.assertOnRenderThread();
        GLCapabilities caps = GL.getCapabilities();
        parallelApi = caps.GL_ARB_parallel_shader_compile && caps.glMaxShaderCompilerThreadsARB != MemoryUtil.NULL ? 1
                : caps.GL_KHR_parallel_shader_compile && caps.glMaxShaderCompilerThreadsKHR != MemoryUtil.NULL ? 2 : 0;
    }

    private void hintThreads() {
        if (parallelApi == 0 || hinted) return;
        hinted = true;
        previousThreadHint = GL11C.glGetInteger(ARBParallelShaderCompile.GL_MAX_SHADER_COMPILER_THREADS_ARB);
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

    /** Owned, unshared shader stage. */
    public int shader(int type, String source, String label) {
        int shader = GlStateManager.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException("Could not create shader " + label);
        Stage stage = new Stage(shader, label, type, source);
        stages.add(stage);
        stageHandles.put(shader, stage);
        GlCompat.safeShaderSource(shader, source);
        return shader;
    }

    public boolean compiled(int shader) {
        return stageHandles.get(shader).compiled;
    }

    private static Stage external(int shader) {
        Stage stage = new Stage(shader, "external", GlStateManager.glGetShaderi(shader, GL20C.GL_SHADER_TYPE),
                GL20C.glGetShaderSource(shader));
        stage.compiled = true;
        return stage;
    }

    public void retainShader(int shader) {
        retainedStages.add(shader);
    }

    public int link(String label, int[] shaders, String[] attributes, IntConsumer publish) {
        int program = GlStateManager.glCreateProgram();
        if (program == 0) throw new IllegalStateException("Could not create program " + label);
        int[] types = new int[shaders.length];
        String[] sources = new String[shaders.length];
        for (int i = 0; i < shaders.length; i++) {
            Stage stage = stageHandles.computeIfAbsent(shaders[i], GlCompilationBatch::external);
            types[i] = stage.type;
            sources[i] = stage.source;
        }
        String key = GlProgramBinaryCache.key(types, sources, attributes);
        Program pending = new Program(program, label, shaders, publish, key);
        programs.add(pending);
        if (key != null && GlProgramBinaryCache.load(key, program)) {
            pending.loaded = true;
            return program;
        }
        hintThreads();
        for (int shader : shaders) {
            Stage stage = stageHandles.get(shader);
            if (!stage.compiled) {
                GlStateManager.glCompileShader(shader);
                stage.compiled = true;
            }
            GlStateManager.glAttachShader(program, shader);
        }
        pending.attached = true;
        for (int location = 0; location < attributes.length; location++) {
            if (attributes[location] != null)
                GlStateManager._glBindAttribLocation(program, location, attributes[location]);
        }
        if (key != null) GlProgramBinaryCache.retrievable(program);
        GlStateManager.glLinkProgram(program);
        return program;
    }

    /** Validates before shader/program ownership transfer. */
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
        for (Stage stage : stages) {
            if (stage.compiled && GlStateManager.glGetShaderi(stage.handle, GL20C.GL_COMPILE_STATUS) == 0) {
                Set<String> failed = new LinkedHashSet<>();
                for (Program program : programs) {
                    if (program.loaded) continue;
                    for (int shader : program.shaders) {
                        if (shader == stage.handle) {
                            failed.add(program.label);
                            break;
                        }
                    }
                }
                throw failure("Failed to compile " + stage.label + ": "
                        + GlStateManager.glGetShaderInfoLog(stage.handle, 32768), failed);
            }
        }
        int loaded = 0;
        for (Program program : programs) {
            if (program.loaded) {
                loaded++;
                continue;
            }
            int status = GlStateManager.glGetProgrami(program.handle, GL20C.GL_LINK_STATUS);
            String log = GlStateManager.glGetProgramInfoLog(program.handle, 32768);
            if (status == 0 || log.contains("Failed for unknown reason")) {
                throw failure("Failed to link " + program.label + ": " + log, Set.of(program.label));
            }
            if (!log.isEmpty()) FlwBackend.LOGGER.info("Info log when linking {}: {}", program.label, log);
        }
        for (Program program : programs) {
            if (!program.loaded && program.key != null) GlProgramBinaryCache.store(program.key, program.handle);
        }
        publishShaders.run();
        for (Program program : programs) {
            program.publish.accept(program.handle);
            program.transferred = true;
        }
        int compiledStages = 0;
        for (Stage stage : stages) if (stage.compiled) compiledStages++;
        FlwBackend.LOGGER.info("shader batch: {} stages ({} compiled), {} reused stages, {} programs ({} from binaries, "
                        + "{} rejected, {} KiB stored), {} pending at validation, parallel API {}", stages.size(),
                compiledStages, reusedStages, programs.size(), loaded, GlProgramBinaryCache.rejected - rejectedBefore,
                (GlProgramBinaryCache.storedBytes - storedBefore) / 1024, pendingAtValidation, parallelApi);
    }

    @Override
    public void close() {
        for (Program program : programs) {
            if (program.attached) for (int shader : program.shaders) GL20C.glDetachShader(program.handle, shader);
            if (!program.transferred) GlStateManager.glDeleteProgram(program.handle);
        }
        for (Stage stage : stages) {
            if (!retainedStages.contains(stage.handle)) GlStateManager.glDeleteShader(stage.handle);
        }
        if (hinted) setThreadHint(previousThreadHint);
    }

    private static Failure failure(String message, Set<String> programs) {
        int error = GlStateManager._getError();
        if (error == GL11C.GL_OUT_OF_MEMORY) throw new OutOfMemoryError(message);
        if (error == GL45C.GL_CONTEXT_LOST) throw new IllegalStateException("OpenGL context lost: " + message);
        return new Failure(message, programs);
    }

    public static final class Failure extends BackendUnavailableException {
        private final Set<String> programs;

        private Failure(String message, Set<String> programs) {
            super(message);
            this.programs = Set.copyOf(programs);
        }

        public Set<String> programs() {
            return programs;
        }
    }

    private record StageKey(int type, String source) {
    }

    private static final class Stage {
        private final int handle;
        private final String label;
        private final int type;
        private final String source;
        private boolean compiled;

        private Stage(int handle, String label, int type, String source) {
            this.handle = handle;
            this.label = label;
            this.type = type;
            this.source = source;
        }
    }

    private static final class Program {
        private final int handle;
        private final String label;
        private final int[] shaders;
        private final IntConsumer publish;
        private final @Nullable String key;
        private boolean loaded;
        private boolean attached;
        private boolean transferred;

        private Program(int handle, String label, int[] shaders, IntConsumer publish, @Nullable String key) {
            this.handle = handle;
            this.label = label;
            this.shaders = shaders;
            this.publish = publish;
            this.key = key;
        }
    }
}
