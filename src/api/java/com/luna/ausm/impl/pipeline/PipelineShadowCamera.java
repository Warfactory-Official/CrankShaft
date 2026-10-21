package com.luna.ausm.impl.pipeline;

abstract class PipelineShadowCamera extends PipelineRuntimeStateBase {
    protected double shadowRenderCullDistance() {
        throw new UnsupportedOperationException();
    }
}
