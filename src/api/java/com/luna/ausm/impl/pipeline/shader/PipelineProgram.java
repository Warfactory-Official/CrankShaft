package com.luna.ausm.impl.pipeline.shader;

import com.luna.ausm.api.pipeline.fbo.Attachment;
import com.luna.ausm.api.pipeline.pack.ShaderProgramDirectives;
import com.luna.ausm.api.pipeline.shader.RenderPass;

import java.util.List;
import java.util.Map;

public class PipelineProgram {
    public RenderPass pass() {
        throw new UnsupportedOperationException();
    }

    public ShaderProgramDirectives directives() {
        throw new UnsupportedOperationException();
    }

    public List<Attachment> drawBuffers() {
        throw new UnsupportedOperationException();
    }

    public boolean hasOwnProgram() {
        throw new UnsupportedOperationException();
    }

    public ShaderProgram shaderProgram() {
        throw new UnsupportedOperationException();
    }

    public List<Attachment> effectiveDrawBuffers(Map<RenderPass, PipelineProgram> programs) {
        throw new UnsupportedOperationException();
    }
}
