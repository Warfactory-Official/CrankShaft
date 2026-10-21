package com.luna.ausm.impl.pipeline.fbo;

public abstract class DeferredFramebuffer extends DeferredFramebufferBase {
    public abstract int getWidth();

    public abstract int getHeight();

    public abstract int getFramebufferId();

    public abstract int getDepthTexture();

    public abstract int getDepthSamplerTexture(int index);
}
