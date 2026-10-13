package dev.engine_room.flywheel.backend.engine;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.FabulousOitPipelines;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;

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

    /**
     * Resolved-layer prepasses (clouds, layered weather). MUST run before any producer pass opens.
     */
    public static void prepass(CommandEncoder encoder, FabulousCaptures capture, OitFramebuffer framebuffer) {
        CloudsOitReplay.prepass(encoder, capture, framebuffer);
        if (!OitConfig.exactFabulous()) {
            WeatherOitReplay.prepass(encoder, capture, framebuffer);
        }
    }

    /**
     * Every captured layer into one wavelet producer pass; exact weather replays as geometry.
     */
    public static void replayCaptures(RenderPass pass, FabulousCaptures capture, OitMode mode,
                                      OitFramebuffer framebuffer, OitFrame f) {
        if (capture.hasClouds()) {
            replay(pass, framebuffer.cloudsColorView(), framebuffer.cloudsDepthView(), capture.cloudsTransform, mode,
                    framebuffer, f.blueNoiseView(), f.oitSampler(), f.noiseSampler());
        }
        if (capture.hasItemLayer()) {
            replay(pass, capture.itemLayerColor, capture.itemLayerDepth, f.dynamicTransforms(), mode, framebuffer,
                    f.blueNoiseView(), f.oitSampler(), f.noiseSampler());
        }
        if (capture.hasParticleLayer()) {
            replay(pass, capture.particleLayerColor, capture.particleLayerDepth, f.dynamicTransforms(), mode,
                    framebuffer, f.blueNoiseView(), f.oitSampler(), f.noiseSampler());
        }
        if (!capture.hasWeather()) {
            return;
        }
        if (OitConfig.exactFabulous()) {
            WeatherOitReplay.replay(pass, capture, mode, framebuffer, f.lightmapView(), f.blueNoiseView(),
                    f.loSampler(), f.oitSampler(), f.noiseSampler(), f.textureManager());
        } else {
            replay(pass, framebuffer.weatherColorView(), framebuffer.weatherDepthView(), f.dynamicTransforms(), mode,
                    framebuffer, f.blueNoiseView(), f.oitSampler(), f.noiseSampler());
        }
    }

    public static void replay(RenderPass pass, GpuTextureView colorView, GpuTextureView depthView,
                              GpuBufferSlice transforms, OitMode mode, OitFramebuffer framebuffer,
                              GpuTextureView blueNoiseView, GpuSampler oitSampler, GpuSampler noiseSampler) {
        pass.setRenderPipeline(FabulousOitPipelines.layerProducer(mode));
        // The fullscreen pipeline declares NO vertex bindings, but this shared producer pass usually has a
        // vertex buffer at slot 0 (the instance meshes, or a prior replay's last draw). The GL RHI walks the
        // PASS's non-null vertex buffers and dereferences the PIPELINE's binding for each
        // (VertexArrayCache$Separate), so a stale slot 0 under a bindingless pipeline NPEs.
        pass.setVertexBuffer(0, null);
        // The MATRICES snippet's bind group declares DynamicTransforms and the RHI requires every
        // bind-group UBO to be set; the fullscreen vertex doesn't read it.
        pass.setUniform("DynamicTransforms", transforms);
        pass.setUniform("_flw_layerColor", colorView, oitSampler);
        pass.setUniform("_flw_layerDepth", depthView, oitSampler);
        framebuffer.bindOitReads(pass, mode, blueNoiseView, oitSampler, noiseSampler);

        pass.draw(3, 1, 0, 0);
    }
}
