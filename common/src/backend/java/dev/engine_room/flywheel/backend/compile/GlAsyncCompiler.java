package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ARBParallelShaderCompile;
import org.lwjgl.opengl.GL20C;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Render-thread background compile/link through {@code ARB/KHR_parallel_shader_compile}: {@link #submit} returns at
 * once and {@link #poll} publishes completed programs, so publication happens only where the owner polls. In-flight
 * programs share stages by source; programs linked from source store their binaries.
 */
public final class GlAsyncCompiler {
    private static final Map<StageKey, Stage> STAGES = new HashMap<>();
    private static final List<Program> PROGRAMS = new ArrayList<>();
    private static final List<Integer> RETIRED = new ArrayList<>();
    private static int api = -1;
    private static boolean hinted;
    private static int previousThreadHint;

    private GlAsyncCompiler() {
    }

    /**
     * Whether submissions compile in the background; without it, callers compile synchronously.
     */
    public static boolean available() {
        if (api < 0) api = GlCompilationBatch.parallelApi();
        return api != 0;
    }

    /**
     * After {@link #available}. {@code beforeLink} sees the program before a link from source (not before a binary
     * load); {@code publish} receives the linked program from a later {@link #poll}. A failure throws from that poll,
     * attributed to {@code owner} when non-null.
     */
    public static Pending submit(String label, int[] types, String[] sources, String[] stageLabels,
                                 String[] attributes, ProgramAvailability.@Nullable Feature owner,
                                 @Nullable IntConsumer beforeLink, IntConsumer publish) {
        return submit(label, types, sources, stageLabels, attributes, owner, beforeLink, publish, null);
    }

    public static Pending submit(String label, int[] types, String[] sources, String[] stageLabels,
                                 String[] attributes, ProgramAvailability.@Nullable Feature owner,
                                 @Nullable IntConsumer beforeLink, IntConsumer publish, @Nullable Runnable onFailure) {
        RenderSystem.assertOnRenderThread();
        String key = GlProgramBinaryCache.key(types, sources, attributes);
        int handle = GlStateManager.glCreateProgram();
        if (handle == 0) throw new IllegalStateException("Could not create program " + label);
        Program program = new Program(label, handle, key, owner, publish, onFailure);
        PROGRAMS.add(program);
        try {
            if (key != null && GlProgramBinaryCache.load(key, handle)) {
                program.loaded = true;
                return program;
            }
            if (!hinted) {
                hinted = true;
                previousThreadHint = GlCompilationBatch.threadHint();
                GlCompilationBatch.threadHint(api, -1);
            }
            program.stages = new Stage[types.length];
            for (int i = 0; i < types.length; i++) {
                StageKey stageKey = new StageKey(types[i], sources[i]);
                Stage stage = STAGES.get(stageKey);
                if (stage == null) {
                    stage = new Stage(stageKey, stageLabels[i]);
                    STAGES.put(stageKey, stage);
                }
                stage.users++;
                program.stages[i] = stage;
                GlStateManager.glAttachShader(handle, stage.handle);
            }
            for (int location = 0; location < attributes.length; location++) {
                if (attributes[location] != null)
                    GlStateManager._glBindAttribLocation(handle, location, attributes[location]);
            }
            if (beforeLink != null) beforeLink.accept(handle);
            if (key != null) GlProgramBinaryCache.retrievable(handle);
            GlStateManager.glLinkProgram(handle);
            return program;
        } catch (RuntimeException | Error failure) {
            PROGRAMS.remove(program);
            program.discard();
            if (PROGRAMS.isEmpty()) restoreThreads();
            throw failure;
        }
    }

    /**
     * A published program no longer bound, deleted at the next {@link #poll}.
     */
    public static void retire(int program) {
        RETIRED.add(program);
    }

    /**
     * Deletes retired programs and publishes every completed program in submission order.
     */
    public static void poll() {
        RenderSystem.assertOnRenderThread();
        if (!RETIRED.isEmpty()) {
            for (int program : RETIRED) GlStateManager.glDeleteProgram(program);
            RETIRED.clear();
        }
        if (PROGRAMS.isEmpty()) return;
        int published = 0;
        int loaded = 0;
        long storedBefore = GlProgramBinaryCache.storedBytes;
        for (int i = 0; i < PROGRAMS.size(); ) {
            Program program = PROGRAMS.get(i);
            if (!program.loaded && GlStateManager.glGetProgrami(program.handle,
                    ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB) == 0) {
                i++;
                continue;
            }
            PROGRAMS.remove(i);
            try {
                finish(program);
            } catch (RuntimeException | Error failure) {
                if (program.onFailure != null) program.onFailure.run();
                if (PROGRAMS.isEmpty()) restoreThreads();
                throw failure;
            }
            published++;
            if (program.loaded) loaded++;
        }
        if (PROGRAMS.isEmpty()) restoreThreads();
        if (published > 0) {
            FlwBackend.LOGGER.info("background shader programs: {} published ({} from binaries, {} KiB stored), {} "
                            + "pending", published, loaded, (GlProgramBinaryCache.storedBytes - storedBefore) / 1024,
                    PROGRAMS.size());
        }
    }

    /**
     * Deletes every in-flight program unpublished.
     */
    public static void reset() {
        RenderSystem.assertOnRenderThread();
        for (Program program : List.copyOf(PROGRAMS)) program.cancel();
    }

    private static void finish(Program program) {
        if (!program.loaded) {
            for (Stage stage : program.stages) {
                if (GlStateManager.glGetShaderi(stage.handle, GL20C.GL_COMPILE_STATUS) == 0) {
                    String log = GlStateManager.glGetShaderInfoLog(stage.handle, 32768);
                    program.discard();
                    throw program.failure("Failed to compile " + stage.label + ": " + log);
                }
            }
            String log = GlStateManager.glGetProgramInfoLog(program.handle, 32768);
            boolean linked = GlStateManager.glGetProgrami(program.handle, GL20C.GL_LINK_STATUS) != 0
                    && !log.contains("Failed for unknown reason");
            program.releaseStages();
            if (!linked) {
                program.discard();
                throw program.failure("Failed to link " + program.label + ": " + log);
            }
            if (program.key != null) GlProgramBinaryCache.store(program.key, program.handle);
            if (!log.isEmpty()) FlwBackend.LOGGER.info("Info log when linking {}: {}", program.label, log);
        }
        program.publish.accept(program.handle);
    }

    private static void restoreThreads() {
        if (!hinted) return;
        hinted = false;
        GlCompilationBatch.threadHint(api, previousThreadHint);
    }

    /**
     * A submitted program until {@link #poll} publishes it.
     */
    public interface Pending {
        /**
         * Deletes the program unpublished.
         */
        void cancel();
    }

    private record StageKey(int type, String source) {
    }

    private static final class Stage {
        private final StageKey key;
        private final String label;
        private final int handle;
        private int users;

        private Stage(StageKey key, String label) {
            this.key = key;
            this.label = label;
            handle = GlStateManager.glCreateShader(key.type());
            if (handle == 0) throw new IllegalStateException("Could not create shader " + label);
            GlCompat.safeShaderSource(handle, key.source());
            GlStateManager.glCompileShader(handle);
        }
    }

    private static final class Program implements Pending {
        private final String label;
        private final int handle;
        private final @Nullable String key;
        private final ProgramAvailability.@Nullable Feature owner;
        private final IntConsumer publish;
        private final @Nullable Runnable onFailure;
        private Stage @Nullable [] stages;
        private boolean loaded;

        private Program(String label, int handle, @Nullable String key, ProgramAvailability.@Nullable Feature owner,
                        IntConsumer publish, @Nullable Runnable onFailure) {
            this.label = label;
            this.handle = handle;
            this.key = key;
            this.owner = owner;
            this.publish = publish;
            this.onFailure = onFailure;
        }

        @Override
        public void cancel() {
            if (!PROGRAMS.remove(this)) return;
            releaseStages();
            discard();
            if (PROGRAMS.isEmpty()) restoreThreads();
        }

        private void releaseStages() {
            if (stages == null) return;
            for (Stage stage : stages) {
                if (stage == null) continue;
                GL20C.glDetachShader(handle, stage.handle);
                if (--stage.users == 0) {
                    STAGES.remove(stage.key);
                    GlStateManager.glDeleteShader(stage.handle);
                }
            }
            stages = null;
        }

        private void discard() {
            releaseStages();
            GlStateManager.glDeleteProgram(handle);
        }

        private BackendUnavailableException failure(String message) {
            BackendUnavailableException failure = GlCompilationBatch.failure(message, Set.of(label));
            return owner == null ? failure : new ProgramAvailability.Failure(owner, failure);
        }
    }
}
