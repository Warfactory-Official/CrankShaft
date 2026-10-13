package dev.engine_room.flywheel.compat.backend;

#if MC >= 26.3
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
#else
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
#endif

import java.util.function.Supplier;

/** Expands to the native render-pass calls for the selected Minecraft version; shared code calls the 26.3 shape. */
public macro class RenderPassOps {
    #if MC >= 26.3
    RenderPassDescriptor.Builder builder(Supplier<String> label) {
        return RenderPassDescriptor.builder(label);
    }

    void RenderPass.setRenderPipeline(RenderPipeline pipeline) {
        this.setPipeline(RenderPipelineCompiler.get(pipeline));
    }
    #else
    RenderPassDescriptor builder(Supplier<String> label) {
        return RenderPassDescriptor.create(label);
    }

    RenderPassDescriptor RenderPassDescriptor.build() {
        if (this.renderArea == null) {
            GpuTextureView extent = this.colorAttachments.isEmpty() ? this.depthAttachment.textureView()
                    : this.colorAttachments.getFirst().textureView();
            this.renderArea = new RenderPass.RenderArea(0, 0, extent.getWidth(0), extent.getHeight(0));
        }
        return this;
    }

    void RenderPass.setRenderPipeline(RenderPipeline pipeline) {
        this.setPipeline(pipeline);
    }

    void RenderPass.setUniform(String name, GpuTextureView texture, GpuSampler sampler) {
        this.bindTexture(name, texture, sampler);
    }
    #endif
}
