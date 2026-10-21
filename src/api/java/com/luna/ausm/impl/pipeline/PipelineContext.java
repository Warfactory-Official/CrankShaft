package com.luna.ausm.impl.pipeline;

import com.luna.ausm.api.pipeline.fbo.Attachment;
import com.luna.ausm.api.pipeline.shader.RenderPass;
import com.luna.ausm.api.pipeline.shader.WorldRenderingPhase;
import com.luna.ausm.impl.pipeline.pack.ShaderProperties;
import com.luna.ausm.impl.pipeline.resource.ShaderStorageBufferSet;
import com.luna.ausm.impl.pipeline.shader.PipelineProgram;
import com.luna.ausm.impl.pipeline.shader.ShaderProgram;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Map;

public class PipelineContext extends PipelineWorldFramebufferFinalization {
    public String activePackName;
    public Map<RenderPass, PipelineProgram> programs;
    public ShaderProperties shaderProperties;
    public ShaderStorageBufferSet shaderStorageBuffers;
    public int currentEntityId;
    public int dynamicBlockEntityId;
    public int currentRenderedItemId;
    public float[] currentEntityColor;
    public ResourceLocation currentEntityKey;
    public float currentAlphaTestReference;

    public static PipelineContext getInstance() {
        throw new UnsupportedOperationException();
    }

    public boolean isActive() {
        throw new UnsupportedOperationException();
    }

    public boolean isShadowPassActive() {
        throw new UnsupportedOperationException();
    }

    public boolean isBloomTranslucentAttenuationPass() {
        throw new UnsupportedOperationException();
    }

    public boolean shouldDisableVanillaEntityShadows() {
        throw new UnsupportedOperationException();
    }

    public void beginPass(RenderPass pass, WorldRenderingPhase phase) {
        throw new UnsupportedOperationException();
    }

    public boolean bindPass(RenderPass pass) {
        throw new UnsupportedOperationException();
    }

    public void setPhase(WorldRenderingPhase phase) {
        throw new UnsupportedOperationException();
    }

    public void endPass() {
        throw new UnsupportedOperationException();
    }

    public void bindProgramResources(RenderPass pass, ShaderProgram program) {
        throw new UnsupportedOperationException();
    }

    public void applyBlendMode(RenderPass pass, List<Attachment> buffers) {
        throw new UnsupportedOperationException();
    }

    public PipelineProgram effectivePipelineProgram(RenderPass pass) {
        throw new UnsupportedOperationException();
    }

    public void configureGbufferDrawBuffers(PipelineProgram program, List<Attachment> buffers) {
        throw new UnsupportedOperationException();
    }

    public void configureShadowDrawBuffers(PipelineProgram program, List<Attachment> buffers) {
        throw new UnsupportedOperationException();
    }
}
