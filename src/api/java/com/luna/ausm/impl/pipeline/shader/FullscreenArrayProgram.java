package com.luna.ausm.impl.pipeline.shader;

import com.luna.ausm.api.pipeline.fbo.Attachment;
import com.luna.ausm.api.pipeline.pack.ShaderProgramDirectives;

import java.util.List;

public abstract class FullscreenArrayProgram {
    public abstract int index();

    public abstract String name();

    public abstract ShaderProgram shaderProgram();

    public abstract ShaderProgramDirectives directives();

    public abstract List<Attachment> drawBuffers();

    public abstract boolean hasProgram();
}
