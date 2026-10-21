package com.luna.ausm.impl.pipeline.shader;

import com.luna.ausm.api.pipeline.shader.RenderPass;

import java.util.List;

public abstract class FullscreenProgramArray {
    public abstract List<RenderPass> fixedPasses();
}
