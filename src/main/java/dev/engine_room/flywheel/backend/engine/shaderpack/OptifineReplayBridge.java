package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import net.optifine.shaders.Program;
import net.optifine.shaders.Shaders;
import org.lwjgl.opengl.*;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class OptifineReplayBridge {
    private static final List<Integer> BINDINGS = List.of(0, 1, 2);
    private static State state;
    private static CompositeScope active;

    private OptifineReplayBridge() {
    }

    private static State inputs() {
        if (!Shaders.shaderPackLoaded || !Shaders.isRenderingWorld) return null;
        var pack = Shaders.getShaderPack();
        var plan = pack instanceof OptifineShaderPack patched ? patched.plan() : null;
        if (plan == null || plan.replayProfile() == null) return null;
        if (state == null || state.plan != plan) {
            close();
            state = new State(plan);
        }
        if (state.targets.resize()) state.captureFrame = Integer.MIN_VALUE;
        return state;
    }

    public static void bindProgram(Program program) {
        var handle = program.getId();
        if (handle == 0) return;
        var state = inputs();
        if (state == null) return;
        GlStateTracker._setProgram(handle);
        var frame = Shaders.frameCounter;
        if (!state.rejected && state.captureFrame != frame) {
            state.clear.clear(state.targets, state.targets.width(), state.targets.height());
            state.captureFrame = frame;
        }
        state.bind();
        var uniform = state.uniforms.get(program);
        if (uniform == null || uniform.program != handle) {
            uniform = new CaptureUniform(handle, GL20.glGetUniformLocation(handle, "flw_oitActive"));
            state.uniforms.put(program, uniform);
        }
        if (uniform.location >= 0) GL45.glProgramUniform1i(handle, uniform.location, state.rejected ? 0 : 1);
    }

    public static CompositeScope composites(Program[] programs, boolean finalOutput) {
        if (!finalOutput || Shaders.isShadowPass) return null;
        var state = inputs();
        if (state == null || state.rejected) return null;
        if (active != null) throw new IllegalStateException("Nested OptiFine material composites");
        if (state.captureFrame != Shaders.frameCounter)
            throw new IllegalStateException("Deferred capture was not initialized before OptiFine composites");
        if (state.renderer == null) {
            state.passes = new OptifineReplayPasses(programs, state.targets);
            var plan = state.adapter.replayPlan(state.targets, state.passes.passes());
            if (plan == null) throw new IllegalStateException("Checked SEUS composite stages are unavailable");
            state.renderer = new DeferredOitRenderer(plan, state.passes.passes(), state.targets);
        }
        return active = new CompositeScope(state);
    }

    public static void beforeProgram(Program program) {
        if (active != null) active.before(program);
    }

    public static void beforeFinal() {
        if (active != null) active.finish(true);
    }

    public static boolean graphics() {
        return DeferredReplayExecution.active() && DeferredReplayExecution.graphics(
                GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
    }

    static Iterable<Integer> storageBindings() {
        return state == null ? List.of() : BINDINGS;
    }

    static int storageHandle(int binding) {
        return state.targets.buffer(binding);
    }

    static void restoreStorage() {
        if (state != null) state.bind();
    }

    public static void close() {
        if (active != null) active.close();
        var previous = state;
        state = null;
        if (previous != null) previous.close();
    }

    public static final class CompositeScope implements AutoCloseable {
        private final State owner;
        private DeferredReplayExecution execution;
        private boolean prepared;
        private boolean completed;

        private CompositeScope(State owner) {
            this.owner = owner;
        }

        private void before(Program program) {
            var pass = owner.passes.find(program);
            if (pass == null) return;
            if (execution != null) {
                execution.close();
                execution = null;
            }
            if (!prepared) {
                owner.passes.prepareFrame();
                prepared = true;
            }
            owner.bind();
            owner.renderer.before(pass);
            owner.passes.prepareNative(pass);
            execution = new DeferredReplayExecution(owner.renderer, pass, 0, false);
        }

        private void finish(boolean successful) {
            if (execution != null) {
                execution.close();
                execution = null;
            }
            owner.renderer.finish(successful);
        }

        public void completed() {
            completed = true;
        }

        @Override
        public void close() {
            try {
                finish(completed);
            } finally {
                active = null;
            }
        }
    }

    private static final class State implements AutoCloseable {
        private final ContractPatches.Plan plan;
        private final DeferredOitAdapter adapter;
        private final OptifineReplayTargets targets = new OptifineReplayTargets();
        private final DeferredCaptureClear clear = new DeferredCaptureClear(false);
        private final Map<Program, CaptureUniform> uniforms = new WeakHashMap<>();
        private OptifineReplayPasses passes;
        private DeferredOitRenderer renderer;
        private boolean rejected;
        private int captureFrame = Integer.MIN_VALUE;

        State(ContractPatches.Plan plan) {
            this.plan = plan;
            adapter = DeferredOitProfile.valueOf(plan.replayProfile()).adapter();
            try {
                if (!plan.replayProfile().equals(DeferredOitProfile.SEUS.name()))
                    throw new UnsupportedOperationException(
                            "This profile needs native targets or compute stages absent from OptiFine 1.12");
                adapter.validate(targets);
            } catch (UnsupportedOperationException error) {
                rejected = true;
                FlwBackend.LOGGER.warn("Deferred material ABI rejected; retaining native OptiFine rendering: {}",
                        error.getMessage());
            }
        }

        private void bind() {
            for (var binding : BINDINGS)
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, targets.buffer(binding));
        }

        @Override
        public void close() {
            try {
                if (renderer != null) renderer.close();
            } finally {
                try {
                    clear.close();
                } finally {
                    targets.close();
                }
            }
        }
    }

    private record CaptureUniform(int program, int location) {
    }
}
