package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Resolves vanilla's captured clouds into a layer for the OIT chain: {@link #prepass} renders the captured
 * cloud state with vanilla's own CLOUDS/FLAT_CLOUDS pipeline into flywheel-owned color+depth targets, BEFORE
 * the producer passes open -- pixel-identical to vanilla clouds, and crucially the depth-writes resolve cloud
 * SELF-occlusion (vanilla clouds are translucent vs the world but OPAQUE vs themselves; per-face OIT
 * producers would accumulate every face of the cloud volume and render its interior structure).
 * {@link LayerOitReplay} then emits the resolved pair as ONE fullscreen translucent surface per producer mode.
 */
public final class CloudsOitReplay {
    private CloudsOitReplay() {
    }

    /**
     * Render the captured cloud state into the resolved layer. MUST run before any producer pass opens.
     */
    public static void prepass(CommandEncoder encoder, FabulousCaptures capture, OitFramebuffer framebuffer) {
        if (!capture.hasClouds()) {
            return;
        }
        framebuffer.prepareCloudsLayer();
        // Depth-seed from the opaque main depth (opaque is final by this seam): world-occluded cloud fragments
        // die at capture, so downstream consumers need no per-sample opaque test.
        framebuffer.seedCloudsDepth(encoder, Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTexture());

        try (RenderPass pass = encoder.createRenderPass(framebuffer.cloudsLayerDescriptor())) {
            // Vanilla's own registered pipeline: TRANSLUCENT blend + depth WRITE (the self-occlusion resolve).
            pass.setPipeline(capture.cloudsFancy ? RenderPipelines.CLOUDS : RenderPipelines.FLAT_CLOUDS);
            RenderSystem.bindDefaultUniforms(pass);
            // Camera-relative geometry: the level model-view written at capture time.
            pass.setUniform("DynamicTransforms", capture.cloudsTransform);
            pass.setUniform("CloudInfo", capture.cloudInfo);
            pass.setUniform("CloudFaces", capture.cloudFaces);
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            pass.setIndexBuffer(indices.getBuffer(6 * capture.cloudQuads), indices.type());
            pass.drawIndexed(6 * capture.cloudQuads, 1, 0, 0, 0);
        }
    }
}
