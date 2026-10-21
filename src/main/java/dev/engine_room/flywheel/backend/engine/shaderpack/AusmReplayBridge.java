package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.luna.ausm.api.pipeline.shader.ProgramArrayId;
import com.luna.ausm.impl.pipeline.PipelineContext;
import com.luna.ausm.impl.pipeline.shader.ShaderProgram;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

import java.util.List;

public final class AusmReplayBridge {
    private static AusmState ausm;

    private AusmReplayBridge() {
    }

    public static void closeAusm() {
        var previous = ausm;
        ausm = null;
        if (previous != null) previous.close();
    }

    private static ContractPatches.Plan plan(PipelineContext context) {
        return AusmContractShaderProperties.of(context.shaderProperties).flywheel$patches();
    }

    private static AusmState inputs(PipelineContext context) {
        var plan = plan(context);
        if (plan == null || plan.replayProfile() == null) return null;
        if (ausm != null && ausm.plan == plan && ausm.targets.matches(context)) return ausm;
        closeAusm();
        ausm = new AusmState(context, plan);
        return ausm;
    }

    public static void bindAusmProgram(ShaderProgram shader) {
        var program = shader.getId();
        var context = PipelineContext.getInstance();
        if (!context.isActive()) return;
        var plan = plan(context);
        if (plan == null || plan.replayProfile() == null) return;
        GlStateTracker._setProgram(program);
        var state = inputs(context);
        var frame = context.pipelineFrameId;
        if (!state.rejected && state.captureFrame != frame) state.clearCapture(frame);
        var location = shader.getUniformLocation("flw_oitActive");
        GL45.glProgramUniform1i(program, location, state.rejected ? 0 : 1);
    }

    public static boolean runAusmArray(Object pipeline, ProgramArrayId array) {
        if (array != ProgramArrayId.COMPOSITE) return false;
        var context = (PipelineContext) pipeline;
        var state = inputs(context);
        if (state == null || state.rejected) return false;
        if (state.captureFrame != context.pipelineFrameId)
            throw new IllegalStateException("Deferred capture was not initialized before native composites");
        // World guests borrow the low SSBO bindings; native capture must be rebound before its raw kernels.
        context.shaderStorageBuffers.bind();
        if (state.renderer == null) {
            state.passes = AusmReplayPasses.create(context, array, state.targets);
            var replay = state.adapter.replayPlan(state.targets, state.passes);
            if (replay == null) throw new IllegalStateException("Checked material composite stages are unavailable");
            state.renderer = new DeferredOitRenderer(replay, state.passes, state.targets);
        }
        AusmReplayPasses.prepareFrame(state.passes, state.targets);
        try {
            for (var pass : state.passes) state.renderer.executeNative(pass);
            state.renderer.finish(true);
        } catch (RuntimeException | Error error) {
            state.renderer.finish(false);
            throw error;
        }
        return true;
    }

    public static boolean graphics() {
        return DeferredReplayExecution.active() && DeferredReplayExecution.graphics(
                GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
    }

    public static boolean compute(int x, int y, int z) {
        return DeferredReplayExecution.active() && DeferredReplayExecution.compute(
                GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), x, y, z);
    }

    public static boolean computeIndirect(long offset) {
        return DeferredReplayExecution.active() && DeferredReplayExecution.computeIndirect(
                GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                GL11.glGetInteger(GL43.GL_DISPATCH_INDIRECT_BUFFER_BINDING), offset);
    }

    private static final class AusmState {
        private final ContractPatches.Plan plan;
        private final AusmReplayTargets targets;
        private final DeferredOitAdapter adapter;
        private final DeferredCaptureClear clear;
        private List<DeferredReplayPass> passes;
        private DeferredOitRenderer renderer;
        private boolean rejected;
        private long captureFrame = Long.MIN_VALUE;

        AusmState(PipelineContext context, ContractPatches.Plan plan) {
            this.plan = plan;
            adapter = DeferredOitProfile.valueOf(plan.replayProfile()).adapter();
            clear = new DeferredCaptureClear(adapter);
            targets = new AusmReplayTargets(context);
            try {
                adapter.validate(targets);
            } catch (UnsupportedOperationException error) {
                rejected = true;
                FlwBackend.LOGGER.warn("Deferred material ABI rejected; retaining native frame: {}",
                        error.getMessage());
            }
        }

        void clearCapture(long frame) {
            clear.clear(targets, targets.width(), targets.height(), frame);
            captureFrame = frame;
        }

        void close() {
            try {
                if (renderer != null) renderer.close();
            } finally {
                clear.close();
            }
        }
    }
}
