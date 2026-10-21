package com.luna.ausm.impl.pipeline;

import com.luna.ausm.api.pipeline.shader.ProgramArrayId;
import com.luna.ausm.impl.pipeline.fbo.PingPongManager;
import com.luna.ausm.impl.pipeline.resource.ShaderImageSet;
import com.luna.ausm.impl.pipeline.shader.ComputeProgram;
import com.luna.ausm.impl.pipeline.shader.FullscreenArrayProgram;
import com.luna.ausm.impl.pipeline.shader.FullscreenProgramArray;

import java.util.List;
import java.util.Map;

abstract class PipelineRuntimeStateBase {
    protected final PingPongManager pingPongManager = null;
    protected long pipelineFrameId;
    protected ShaderImageSet shaderImages;
    protected final Map<ProgramArrayId, List<ComputeProgram>> computeProgramArrays = null;
    protected final Map<ProgramArrayId, FullscreenProgramArray> fullscreenProgramArrays = null;
    protected final Map<ProgramArrayId, List<FullscreenArrayProgram>> fullscreenArrayPrograms = null;
}
