package com.luna.ausm.api.pipeline.pack;

import com.luna.ausm.api.pipeline.fbo.Attachment;

public abstract class ShaderProgramDirectives {
    public abstract ShaderViewportScale viewportScale();

    public abstract Attachment[] flippedAttachments(Iterable<Attachment> buffers);
}
