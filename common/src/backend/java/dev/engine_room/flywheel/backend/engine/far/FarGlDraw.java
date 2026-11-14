package dev.engine_room.flywheel.backend.engine.far;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlConst;
import com.mojang.renderpearl.backend.opengl.GlDevice;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import com.mojang.renderpearl.backend.opengl.Uniform;
#if MC >= 26.3
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
#endif
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;

import java.util.Map;

/**
 * Binds a prepared far pipeline inside a LoD renderer's GL work without opening a frontend render pass.
 */
public final class FarGlDraw {
    private final GlRenderPipeline pipeline;
    private final Map<String, Uniform> uniforms;
    private final GlProgram uniformLocations;

    public FarGlDraw(RenderPipeline info) {
        #if MC >= 26.3
        var compiled = (FrontendRenderPipeline) RenderPipelineCompiler.get(info);
        pipeline = (GlRenderPipeline) compiled.backendRenderPipeline();
        uniforms = RenderPipelineCompiler.uniforms(info);
        #else
        pipeline = (GlRenderPipeline) RenderSystem.getDevice().precompilePipeline(info, null);
        uniforms = pipeline.program().getUniforms();
        #endif
        uniformLocations = new GlProgram(program());
    }

    public int program() {
        return pipeline.program().getProgramId();
    }

    /**
     * Uniform locations belong to this compiled pipeline; the pipeline retains ownership of its GL program.
     */
    public int uniformLocation(String name) {
        return uniformLocations.getUniformLocation(name);
    }

    public void bind() {
        #if MC >= 26.3
        pipeline.bind();
        #else
        var info = pipeline.info();
        var depth = info.getDepthStencilState();
        if (depth == null) {
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disablePolygonOffset();
        } else {
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GlConst.toGl(depth.depthTest()));
            GlStateManager._depthMask(depth.writeDepth());
            if (depth.depthBiasConstant() == 0.0f && depth.depthBiasScaleFactor() == 0.0f) {
                GlStateManager._disablePolygonOffset();
            } else {
                GlStateManager._polygonOffset(depth.depthBiasScaleFactor(), depth.depthBiasConstant());
                GlStateManager._enablePolygonOffset();
            }
        }
        if (info.isCull()) GlStateManager._enableCull();
        else GlStateManager._disableCull();
        var targets = info.getColorTargetStates();
        for (int i = 0; i < targets.length; i++) {
            var state = targets[i];
            if (state == null) continue;
            GlStateManager._colorMask(i, state.writeMask());
            if (state.blendFunction().isPresent()) {
                var blend = state.blendFunction().get();
                GlStateManager._enableBlend(i);
                GlStateManager._blendFuncSeparate(GlConst.toGl(blend.color().sourceFactor()),
                        GlConst.toGl(blend.color().destFactor()), GlConst.toGl(blend.alpha().sourceFactor()),
                        GlConst.toGl(blend.alpha().destFactor()));
                GlStateManager._blendEquationSeparate(GlConst.toGl(blend.color().op()),
                        GlConst.toGl(blend.alpha().op()));
            } else {
                GlStateManager._disableBlend(i);
            }
        }
        GlStateManager._polygonMode(GL11C.GL_FRONT_AND_BACK, GlConst.toGl(info.getPolygonMode()));
        GlStateManager._glUseProgram(pipeline.program().getProgramId());
        #endif
    }

    public void uniform(String name, GpuBufferSlice slice) {
        switch (uniforms.get(name)) {
            case null -> {
            }
            case Uniform.Ubo block -> GL33C.glBindBufferRange(GL31C.GL_UNIFORM_BUFFER, block.blockBinding(),
                    ((GlBuffer) slice.buffer()).handle(), slice.offset(), slice.length());
            case Uniform.Utb buffer -> {
                #if MC < 26.3
                GlStateManager._glUniform1i(buffer.location(), buffer.samplerIndex());
                #endif
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + buffer.samplerIndex());
                GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, buffer.texture());
                GL31C.glTexBuffer(GL31C.GL_TEXTURE_BUFFER, GlConst.toGlInternalId(buffer.format()),
                        ((GlBuffer) slice.buffer()).handle());
            }
            default -> throw new IllegalArgumentException("Far pipeline buffer uniform is a sampler: " + name);
        }
    }

    public void texture(String name, GpuTextureView view, GpuSampler sampler) {
        var binding = (Uniform.Sampler) uniforms.get(name);
        if (binding == null) return;
        #if MC < 26.3
        GlStateManager._glUniform1i(binding.location(), binding.samplerIndex());
        #endif
        var texture = (GlTextureView) view;
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + binding.samplerIndex());
        GlStateManager._bindTexture(texture.glId());
        GL33C.glBindSampler(binding.samplerIndex(), ((GlSampler) sampler).getId());
    }

    public void vertices(GpuBufferSlice[] slices) {
        #if MC >= 26.3
        pipeline.vertexArray().bind(slices);
        #else
        ((GlDevice) RenderSystem.getDevice().backend).vertexArrayCache()
                                                     .bindVertexArray(pipeline.info().getVertexFormatBindings(), slices,
                                                             null);
        #endif
    }

    public void indices(GpuBuffer buffer) {
        GlStateManager._glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, ((GlBuffer) buffer).handle());
    }
}
