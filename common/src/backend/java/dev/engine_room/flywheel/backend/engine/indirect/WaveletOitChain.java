package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.engine.far.FarGlState;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.compat.backend.RenderPassOps;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL42C;

import java.util.OptionalDouble;

/**
 * The GL wavelet/moment OIT chain: depthRange -> coefficients -> accumulate ->
 * composite over Mojang RenderPasses, with the vanilla replays interleaved into every producer pass. Frames with
 * {@code ORDER_INDEPENDENT_ADDITIVE} producers append emission -> emission composite.
 */
public final class WaveletOitChain {
    private final OitFramebuffer framebuffer = new OitFramebuffer();
    private final RawOitChain mainProducers = RawOitChain.borrowed(framebuffer, null);

    private static RenderPass openProducerPass(OitFrame f, RenderPassDescriptor descriptor) {
        RenderPass pass = f.encoder()
                           .createRenderPass(descriptor);
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", f.dynamicTransforms());
        if (f.vertexBuffer() != null) {
            pass.setVertexBuffer(0, f.vertexBuffer()
                                     .slice());
            pass.setIndexBuffer(f.indexBuffer(), IndexType.INT);
        }
        return pass;
    }

    public OitFramebuffer framebuffer() {
        return framebuffer;
    }

    public boolean render(Matrix4fc renderModelView, @Nullable GpuBuffer vertexBuffer, @Nullable GpuBuffer indexBuffer,
                          boolean hasInstanceOit, boolean hasAdditive, @Nullable Runnable prePass,
                          @Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                          #if MC < 26.3 @Nullable FabulousCaptures fabulous, #endif
                          @Nullable SodiumTerrainOitReplay terrain, @Nullable NativeOitReplay nativeReplay,
                          ProducerGeometry producer) {
        var mainProducer = MainOitHooks.select(null);
        if (mainProducer != null && !mainProducers.borrowedReady()) mainProducer = null;
        boolean hasBer = ber != null && !ber.isEmpty();
        boolean idle = !hasInstanceOit && chunks == null && !hasBer && terrain == null && nativeReplay == null
                && mainProducer == null;
        #if MC < 26.3
        idle &= fabulous == null || !fabulous.hasAny();
        #endif
        if (idle) {
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
        #if MC >= 26.3
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        #else
        RenderTarget translucentTarget = mc.levelRenderer.translucentTarget();
        RenderTarget target = translucentTarget != null ? translucentTarget : mc.gameRenderer.mainRenderTarget();
        #endif
        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return false;
        }

        if (terrain != null) {
            terrain.prepareCull(depthView, target.width, target.height, false);
        }
        if (prePass != null) {
            prePass.run();
        }

        OitFrame frame = OitFrame.prepare(framebuffer, renderModelView, hasInstanceOit ? vertexBuffer : null,
                hasInstanceOit ? indexBuffer : null, blueNoiseView);
        CommandEncoder encoder = frame.encoder();
        if (nativeReplay != null) nativeReplay.prepareWavelet(frame);
        #if MC < 26.3
        if (fabulous != null) {
            LayerOitReplay.prepass(encoder, fabulous, framebuffer);
        }
        #endif

        RawOitTarget mainTarget = mainProducer == null ? null
                : mainProducer.begin(colorView, depthView, target.width, target.height);
        if (mainProducer != null) mainProducers.beginBorrowed(mainTarget);
        float far = FrameUniforms.getDepthFar();

        if (OitFramebuffer.SPLIT_INSTANCE_BOUNDS && frame.vertexBuffer() != null) {
            GlCompat.pushDebugGroup("flywheel:gl/oit/producer/instance_depth_range");
            try (RenderPass pass = openProducerPass(frame, framebuffer.instanceDepthRangeDescriptor(depthView, far))) {
                producer.submit(pass, OitMode.DEPTH_RANGE, frame, false);
            }
            GlCompat.popDebugGroup();
        }

        submitProducerPass(frame, framebuffer.depthRangeDescriptor(depthView, far), OitMode.DEPTH_RANGE, false, chunks,
                ber, #if MC < 26.3 fabulous, #endif terrain, nativeReplay, producer, mainProducer, mainTarget);

        submitProducerPass(frame, framebuffer.coefficientsDescriptor(depthView), OitMode.GENERATE_COEFFICIENTS, false,
                chunks, ber, #if MC < 26.3 fabulous, #endif terrain, nativeReplay, producer, mainProducer, mainTarget);

        // Port: no depth-from-transmittance pass (the depth writeback after the composite). Its write lands up to a bin
        // in front of the layer reaching T <= 1e-4 => EVALUATE culls that layer.
        submitProducerPass(frame, framebuffer.accumulateDescriptor(depthView), OitMode.EVALUATE, false, chunks, ber,
                #if MC < 26.3 fabulous, #endif terrain, nativeReplay, producer, mainProducer, mainTarget);

        hasAdditive |= nativeReplay != null && nativeReplay.hasAdditive();

        // Before the depth writeback: the nearest OIT depth would cull emission behind it.
        if (hasAdditive) {
            framebuffer.prepareEmission();
            submitProducerPass(frame, framebuffer.emissionDescriptor(depthView), OitMode.EVALUATE, true, null, null,
                    #if MC < 26.3 null, #endif null, nativeReplay, producer, mainProducer, mainTarget);
        }

        if (SharedConstants.IS_RUNNING_IN_IDE) {
            GlStateAssert.assertCoherent("oit/pre-composite");
        }

        RenderPassDescriptor compositeDescriptor = RenderPassOps.builder(() -> "flywheel:oit/composite")
                                                                .withColorAttachment(colorView)
                                                                .withDepthAttachment(depthView, OptionalDouble.empty())
                                                                .withRenderArea(new RenderPass.RenderArea(0, 0,
                                                                        target.width, target.height))
                                                                .build();
        GlCompat.pushDebugGroup("flywheel:gl/oit/composite");
        try (RenderPass pass = encoder.createRenderPass(compositeDescriptor)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", frame.dynamicTransforms());
            pass.setRenderPipeline(OitPipelines.composite(hasAdditive));
            pass.setUniform("_flw_accumulate", framebuffer.accumulateView(), frame.oitSampler());
            if (hasAdditive) {
                pass.setUniform("_flw_emission", framebuffer.emissionView(), frame.oitSampler());
            }
            pass.setUniform("_flw_depthRange", framebuffer.depthBoundsView(), frame.oitSampler());
            framebuffer.bindCoefficients(pass, frame.oitSampler());
            pass.draw(3, 1, 0, 0);
            pass.setRenderPipeline(OitPipelines.oitDepth());
            pass.setUniform("_flw_accumulate", framebuffer.accumulateView(), frame.oitSampler());
            pass.setUniform("_flw_depthRange", framebuffer.depthBoundsView(), frame.oitSampler());
            pass.draw(3, 1, 0, 0);
        }
        GlCompat.popDebugGroup();

        if (hasAdditive) {
            RenderPassDescriptor emissionDescriptor =
                    RenderPassOps.builder(() -> "flywheel:oit/emission_composite")
                                 .withColorAttachment(colorView)
                                 .withDepthAttachment(depthView, OptionalDouble.empty())
                                 .withRenderArea(new RenderPass.RenderArea(0, 0, target.width, target.height))
                                 .build();
            GlCompat.pushDebugGroup("flywheel:gl/oit/emission_composite");
            try (RenderPass pass = encoder.createRenderPass(emissionDescriptor)) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", frame.dynamicTransforms());
                pass.setRenderPipeline(OitPipelines.emission());
                pass.setUniform("_flw_accumulate", framebuffer.accumulateView(), frame.oitSampler());
                pass.setUniform("_flw_emission", framebuffer.emissionView(), frame.oitSampler());
                pass.draw(3, 1, 0, 0);
            }
            GlCompat.popDebugGroup();
        }

        return true;
    }

    private void submitProducerPass(OitFrame f, RenderPassDescriptor descriptor, OitMode mode, boolean additive,
                                    @Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                                    #if MC < 26.3 @Nullable FabulousCaptures fabulous, #endif
                                    @Nullable SodiumTerrainOitReplay terrain, @Nullable NativeOitReplay nativeReplay,
                                    ProducerGeometry producer, MainOitHooks.@Nullable Provider mainProducer,
                                    @Nullable RawOitTarget mainTarget) {
        boolean needsColor = mode != OitMode.DEPTH_RANGE;

        GlCompat.pushDebugGroup("flywheel:gl/oit/producer/" + (additive ? "_emission" : mode.name));
        try (RenderPass pass = openProducerPass(f, descriptor)) {
            if (needsColor) {
                pass.setUniform("Sampler1", f.overlayView(), f.loSampler());
                pass.setUniform("Sampler2", f.lightmapView(), f.loSampler());
                framebuffer.bindOitReads(pass, mode, f.blueNoiseView(), f.oitSampler(), f.noiseSampler());
            }

            if (needsColor || !OitFramebuffer.SPLIT_INSTANCE_BOUNDS) {
                producer.submit(pass, mode, f, additive);
            } else if (f.vertexBuffer() != null) {
                pass.setRenderPipeline(OitPipelines.boundsMerge());
                pass.setUniform("_flw_instanceBounds", framebuffer.instanceBoundsView(), f.oitSampler());
                pass.draw(3, 1, 0, 0);
            }

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

            #if MC < 26.3
            if (fabulous != null) {
                LayerOitReplay.replayCaptures(pass, fabulous, mode, framebuffer, f);
            }
            #endif
        }
        GlCompat.popDebugGroup();
        if (nativeReplay != null) {
            nativeReplay.replayWavelet(descriptor, mode, additive, framebuffer, f);
        }
        if (mainProducer != null && !additive) {
            try (var state = FarGlState.capture()) {
                var window = mainProducers.borrowedPass(mainTarget, mode, null);
                window.bind();
                mainProducer.draw(window);
            }
            GL42C.glMemoryBarrier(GL42C.GL_FRAMEBUFFER_BARRIER_BIT | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);
        }
    }

    public void delete() {
        mainProducers.close();
        framebuffer.delete();
    }

    public interface ProducerGeometry {
        /**
         * {@code additive}: submit only {@code ORDER_INDEPENDENT_ADDITIVE} draws (the emission pass); otherwise only
         * the occluding ones.
         */
        void submit(RenderPass pass, OitMode mode, OitFrame f, boolean additive);
    }
}
