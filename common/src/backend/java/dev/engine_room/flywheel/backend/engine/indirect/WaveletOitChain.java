package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.OptionalDouble;

/**
 * The GL wavelet/moment OIT chain: depthRange -> coefficients -> depth-from-transmittance -> accumulate ->
 * composite over Mojang RenderPasses, with the vanilla replays interleaved into every producer pass.
 */
public final class WaveletOitChain {
    private final OitFramebuffer framebuffer = new OitFramebuffer();

    public OitFramebuffer framebuffer() {
        return framebuffer;
    }

    public boolean render(Matrix4fc renderModelView, @Nullable GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer,
                          boolean hasInstanceOit, @Nullable Runnable prePass,
                          @Nullable ChunkSectionsToRender chunks,
                          @Nullable BerTranslucentCapture ber, @Nullable SodiumTerrainOitReplay terrain,
                          @Nullable FabulousCaptures fabulous, ProducerGeometry producer) {
        boolean hasBer = ber != null && !ber.isEmpty();
        boolean hasFabulous = fabulous != null && fabulous.hasAny();
        if (!hasInstanceOit && chunks == null && !hasBer && terrain == null && !hasFabulous) {
            return false;
        }
        if (hasInstanceOit && (vertexBuffer == null || indexBuffer == null)) {
            return false;
        }
        GpuTextureView blueNoiseView = NoiseTextures.BLUE_NOISE == null ? null : NoiseTextures.BLUE_NOISE.getTextureView();
        if (blueNoiseView == null) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget translucentTarget = mc.levelRenderer.translucentTarget();
        RenderTarget target = translucentTarget != null ? translucentTarget : mc.gameRenderer.mainRenderTarget();
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return false;
        }

        if (terrain != null) {
            terrain.prepareCull(depthView, target.width, target.height);
        }
        if (prePass != null) {
            prePass.run();
        }

        OitFrame frame = OitFrame.prepare(framebuffer, renderModelView, hasInstanceOit ? vertexBuffer : null,
                hasInstanceOit ? indexBuffer : null, blueNoiseView);
        CommandEncoder encoder = frame.encoder();

        if (hasFabulous) {
            CloudsOitReplay.prepass(encoder, fabulous, framebuffer);
            if (!OitConfig.exactFabulous()) {
                WeatherOitReplay.prepass(encoder, fabulous, framebuffer);
            }
        }

        float far = FrameUniforms.getDepthFar();

        submitProducerPass(frame, framebuffer.depthRangeDescriptor(depthView, far), OitMode.DEPTH_RANGE, chunks, ber,
                terrain, fabulous, producer);

        submitProducerPass(frame, framebuffer.coefficientsDescriptor(depthView), OitMode.GENERATE_COEFFICIENTS, chunks,
                ber, terrain, fabulous, producer);

        GlCompat.pushDebugGroup("flywheel:gl/oit/transmittance_depth");
        try (RenderPass pass = encoder.createRenderPass(framebuffer.depthFromTransmittanceDescriptor(depthView))) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", frame.dynamicTransforms());
            pass.setPipeline(OitPipelines.depth());
            pass.bindTexture("_flw_depthRange", framebuffer.depthBoundsView(), frame.oitSampler());
            framebuffer.bindCoefficients(pass, frame.oitSampler());
            pass.draw(3, 1, 0, 0);
        }
        GlCompat.popDebugGroup();

        submitProducerPass(frame, framebuffer.accumulateDescriptor(depthView), OitMode.EVALUATE, chunks, ber, terrain,
                fabulous, producer);

        if (SharedConstants.IS_RUNNING_IN_IDE) {
            GlStateAssert.assertCoherent("oit/pre-composite");
        }

        RenderPassDescriptor compositeDescriptor = RenderPassDescriptor.create(() -> "flywheel:oit/composite")
                                                                       .withColorAttachment(colorView)
                                                                       .withDepthAttachment(depthView,
                                                                               OptionalDouble.empty())
                                                                       .withRenderArea(new RenderPass.RenderArea(0, 0,
                                                                               target.width, target.height));
        GlCompat.pushDebugGroup("flywheel:gl/oit/composite");
        try (RenderPass pass = encoder.createRenderPass(compositeDescriptor)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", frame.dynamicTransforms());
            pass.setPipeline(OitPipelines.composite());
            pass.bindTexture("_flw_accumulate", framebuffer.accumulateView(), frame.oitSampler());
            pass.bindTexture("_flw_depthRange", framebuffer.depthBoundsView(), frame.oitSampler());
            framebuffer.bindCoefficients(pass, frame.oitSampler());
            pass.draw(3, 1, 0, 0);
        }
        GlCompat.popDebugGroup();

        return true;
    }

    private void submitProducerPass(OitFrame f, RenderPassDescriptor descriptor, OitMode mode,
                                    @Nullable ChunkSectionsToRender chunks,
                                    @Nullable BerTranslucentCapture ber, @Nullable SodiumTerrainOitReplay terrain,
                                    @Nullable FabulousCaptures fabulous, ProducerGeometry producer) {
        boolean needsColor = mode != OitMode.DEPTH_RANGE;

        GlCompat.pushDebugGroup("flywheel:gl/oit/producer/" + mode.name);
        try (RenderPass pass = f.encoder()
                                .createRenderPass(descriptor)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", f.dynamicTransforms());
            if (f.vertexBuffer() != null) {
                pass.setVertexBuffer(0, f.vertexBuffer()
                                         .slice());
                pass.setIndexBuffer(f.indexBuffer(), IndexType.INT);
            }

            if (needsColor) {
                pass.bindTexture("Sampler1", f.overlayView(), f.loSampler());
                pass.bindTexture("Sampler2", f.lightmapView(), f.loSampler());
                framebuffer.bindOitReads(pass, mode, f.blueNoiseView(), f.oitSampler(), f.noiseSampler());
            }

            producer.submit(pass, mode, f);

            if (chunks != null) {
                ChunkTranslucentReplay.replay(pass, chunks, mode, framebuffer,
                        f.lightmapView(), f.blueNoiseView(), f.loSampler(), f.oitSampler(), f.noiseSampler());
            }

            if (terrain != null) {
                terrain.replay(pass, mode, framebuffer,
                        f.lightmapView(), f.blueNoiseView(), f.loSampler(), f.oitSampler(), f.noiseSampler());
            }

            if (ber != null && !ber.isEmpty()) {
                BerTranslucentReplay.replay(pass, ber, mode, framebuffer,
                        f.lightmapView(), f.overlayView(), f.blueNoiseView(), f.loSampler(), f.oitSampler(),
                        f.noiseSampler());
            }

            if (fabulous != null) {
                if (fabulous.hasClouds()) {
                    LayerOitReplay.replay(pass, framebuffer.cloudsColorView(), framebuffer.cloudsDepthView(),
                            fabulous.cloudsTransform, mode, framebuffer, f.blueNoiseView(), f.oitSampler(),
                            f.noiseSampler());
                }
                if (fabulous.hasItemLayer()) {
                    LayerOitReplay.replay(pass, fabulous.itemLayerColor, fabulous.itemLayerDepth,
                            f.dynamicTransforms(), mode, framebuffer, f.blueNoiseView(), f.oitSampler(),
                            f.noiseSampler());
                }
                if (fabulous.hasParticleLayer()) {
                    LayerOitReplay.replay(pass, fabulous.particleLayerColor, fabulous.particleLayerDepth,
                            f.dynamicTransforms(), mode, framebuffer, f.blueNoiseView(), f.oitSampler(),
                            f.noiseSampler());
                }
                if (fabulous.hasWeather()) {
                    if (OitConfig.exactFabulous()) {
                        WeatherOitReplay.replay(pass, fabulous, mode, framebuffer,
                                f.lightmapView(), f.blueNoiseView(), f.loSampler(), f.oitSampler(), f.noiseSampler(),
                                f.textureManager());
                    } else {
                        LayerOitReplay.replay(pass, framebuffer.weatherColorView(), framebuffer.weatherDepthView(),
                                f.dynamicTransforms(), mode, framebuffer, f.blueNoiseView(), f.oitSampler(),
                                f.noiseSampler());
                    }
                }
            }
        }
        GlCompat.popDebugGroup();
    }

    public void delete() {
        framebuffer.delete();
    }

    public interface ProducerGeometry {
        void submit(RenderPass pass, OitMode mode, OitFrame f);
    }
}
