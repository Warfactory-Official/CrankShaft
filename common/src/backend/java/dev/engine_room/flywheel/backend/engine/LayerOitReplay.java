package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.indirect.OitPipelines;

/**
 * Emits a RESOLVED translucent layer -- a (color, depth) pair holding one already-depth-resolved translucent
 * surface per pixel -- into an open OIT producer pass as ONE fullscreen sample per mode, so the layer
 * depth-interleaves with every other translucent producer (the ordering vanilla Fabulous only gets from the
 * transparency post-chain). Three layers ride this: the clouds prepass output ({@link CloudsOitReplay}) and
 * the redirected item-entity/particle targets ({@link FabulousLayerTargets}, drawn by vanilla itself).
 */
public final class LayerOitReplay {
    private LayerOitReplay() {
    }

    public static void replay(RenderPass pass, GpuTextureView colorView, GpuTextureView depthView,
                              GpuBufferSlice transforms, OitMode mode, OitFramebuffer framebuffer,
                              GpuTextureView blueNoiseView, GpuSampler oitSampler, GpuSampler noiseSampler) {
        pass.setPipeline(OitPipelines.layerProducer(mode));
        // The fullscreen pipeline declares NO vertex bindings, but this shared producer pass usually has a
        // vertex buffer at slot 0 (the instance meshes, or a prior replay's last draw). The GL RHI walks the
        // PASS's non-null vertex buffers and dereferences the PIPELINE's binding for each
        // (VertexArrayCache$Separate), so a stale slot 0 under a bindingless pipeline NPEs.
        pass.setVertexBuffer(0, null);
        // The MATRICES snippet's bind group declares DynamicTransforms and the RHI requires every
        // bind-group UBO to be set; the fullscreen vertex doesn't read it.
        pass.setUniform("DynamicTransforms", transforms);
        pass.bindTexture("_flw_layerColor", colorView, oitSampler);
        pass.bindTexture("_flw_layerDepth", depthView, oitSampler);
        framebuffer.bindOitReads(pass, mode, blueNoiseView, oitSampler, noiseSampler);

        pass.draw(3, 1, 0, 0);
    }
}
