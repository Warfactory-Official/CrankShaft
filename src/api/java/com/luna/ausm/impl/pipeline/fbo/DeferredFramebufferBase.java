package com.luna.ausm.impl.pipeline.fbo;

import com.luna.ausm.api.pipeline.fbo.Attachment;

import java.util.Map;

abstract class DeferredFramebufferBase {
    protected final Map<Attachment, int[]> colorTextures = null;
    protected final Map<Attachment, Boolean> flippedTextures = null;
}
