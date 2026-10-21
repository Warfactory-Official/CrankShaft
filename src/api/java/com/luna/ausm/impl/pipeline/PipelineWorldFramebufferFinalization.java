package com.luna.ausm.impl.pipeline;

import com.luna.ausm.api.pipeline.shader.ProgramArrayId;
import com.luna.ausm.api.pipeline.shader.RenderPass;
import com.luna.ausm.impl.pipeline.shader.ComputeProgram;
import com.luna.ausm.impl.pipeline.shader.FullscreenArrayProgram;
import com.luna.ausm.impl.pipeline.shader.PipelineProgram;

import java.util.List;

abstract class PipelineWorldFramebufferFinalization extends PipelineShadowCamera {
    protected void runFullscreenPass(PipelineProgram program) {
        throw new UnsupportedOperationException();
    }

    protected void runFullscreenArrayProgram(FullscreenArrayProgram program) {
        throw new UnsupportedOperationException();
    }

    protected void runComputeProgramsForArrayIndex(List<ComputeProgram> programs, int index, RenderPass bindingPass) {
        throw new UnsupportedOperationException();
    }

    protected RenderPass computeBindingPass(ProgramArrayId array) {
        throw new UnsupportedOperationException();
    }
}
