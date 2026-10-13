package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.ProgramAvailability;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
import dev.engine_room.flywheel.compat.backend.RenderPassOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.TextureManager;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.OptionalDouble;

final class VkOitRenderer {
    private final VkIndirectDrawManager m;
    private final OitFramebuffer oitFramebuffer = new OitFramebuffer();
    private final VkWaveletOitChain waveletChain;
    @Nullable
    private VkInsertOitChain insertChain;

    VkOitRenderer(VkIndirectDrawManager manager) {
        this.m = manager;
        this.waveletChain = new VkWaveletOitChain(manager, oitFramebuffer);
    }

    boolean render(@Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                   #if MC < 26.3 @Nullable FabulousCaptures fabulous, #endif
                   @Nullable SodiumTerrainOitReplay terrain) {
        return render(#if MC < 26.3 fabulous, #endif chunks, ber, terrain, null);
    }

    boolean renderNative(NativeOitReplay replay) {
        return render(#if MC < 26.3 null, #endif null, null, null, replay);
    }

    private boolean render(#if MC < 26.3 @Nullable FabulousCaptures fabulous, #endif
                           @Nullable ChunkSectionsToRender chunks, @Nullable BerTranslucentCapture ber,
                           @Nullable SodiumTerrainOitReplay terrain, @Nullable NativeOitReplay nativeReplay) {
        boolean hasAdditive = !m.uberOitAdditiveMultiDraws.isEmpty();
        boolean useOit = !m.uberOitMultiDraws.isEmpty() || hasAdditive;
        boolean hasBer = ber != null && !ber.isEmpty();
        boolean idle = !useOit && chunks == null && !hasBer && terrain == null && nativeReplay == null;
        #if MC < 26.3
        boolean hasFabulous = fabulous != null && fabulous.hasAny();
        idle &= !hasFabulous;
        #endif
        if (idle) {
            return false;
        }

        GpuBuffer vertexBuffer = m.meshPool.vertexBuffer();
        GpuBuffer indexBuffer = m.meshPool.indexBuffer();
        if (useOit && (vertexBuffer == null || indexBuffer == null)) {
            return false;
        }
        GpuTextureView blueNoiseTexView = NoiseTextures.BLUE_NOISE == null ? null : NoiseTextures.BLUE_NOISE.getTextureView();
        if (blueNoiseTexView == null) {
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
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice fog = RenderSystem.getShaderFog();
        GpuBufferSlice lights = RenderSystem.getShaderLights();
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        if (projection == null || fog == null || lights == null || globals == null) {
            return false;
        }

        if (terrain != null) {
            terrain.prepareCull(depthView, target.width, target.height, false);
        }

        boolean canInsert = terrain == null || terrain instanceof VkFoldedOitReplay;
        OitInsertMode requestedInsertMode = OitConfig.resolveInsertMode();
        if (nativeReplay != null && requestedInsertMode != null && !nativeReplay.supportsInsert()) {
            throw new UnsupportedOperationException("The native Vulkan terrain producer cannot use "
                    + requestedInsertMode);
        }
        OitInsertMode insertMode = nativeReplay != null || canInsert ? requestedInsertMode : null;
        boolean insert = insertMode != null;
        if (!insert) {
            oitFramebuffer.prepare();
            if (hasAdditive) {
                oitFramebuffer.prepareEmission();
            }
            if (insertChain != null) {
                insertChain.delete();
                insertChain = null;
            }
        } else {
            oitFramebuffer.releaseChainTargets();
            if (insertChain != null && insertChain.mode != insertMode) {
                insertChain.delete();
                insertChain = null;
            }
            if (insertChain == null) {
                insertChain = switch (insertMode) {
                    case KBUFFER -> new VkKbufferOitChain(m, oitFramebuffer);
                    case MLAB -> new VkMlabOitChain(m, oitFramebuffer);
                    case ABUFFER -> new VkAbufferOitChain(m, oitFramebuffer);
                };
            }
            #if MC < 26.3
            if (hasFabulous) {
                oitFramebuffer.prepareSize();
            }
            #endif
        }
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                                                       .writeTransform(new Matrix4f(m.renderModelView));

        GpuSampler atlasSampler = TerrainAtlasFilter.sampler();
        GpuSampler loSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler oitSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuSampler noiseSampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
        GpuTextureView overlayView = mc.gameRenderer.overlayTexture().getTextureView();
        GpuTextureView lightmapView = mc.gameRenderer.lightmap();

        long[] coefficientViews = new long[4];
        long depthBoundsVk = 0L;
        long accumulateVk = 0L;
        if (!insert) {
            for (int i = 0; i < 4; i++) {
                coefficientViews[i] = VkContext.imageView(oitFramebuffer.coefficientsView(i));
            }
            depthBoundsVk = VkContext.imageView(oitFramebuffer.depthBoundsView());
            accumulateVk = VkContext.imageView(oitFramebuffer.accumulateView());
        }
        OitFrame frame = new OitFrame(projection, dynamicTransforms, fog, lights, globals,
                m.renderPassUniforms.renderOriginSlice(), mc.getTextureManager(),
                VkContext.imageView(overlayView),
                VkContext.sampler(loSampler),
                VkContext.imageView(lightmapView),
                VkContext.sampler(atlasSampler),
                depthBoundsVk,
                VkContext.imageView(blueNoiseTexView),
                VkContext.sampler(noiseSampler),
                VkContext.sampler(oitSampler),
                coefficientViews,
                accumulateVk);
        OitReplay replay = new OitReplay(#if MC < 26.3 fabulous, #endif dynamicTransforms, lightmapView, overlayView,
                blueNoiseTexView,
                loSampler, oitSampler, noiseSampler, chunks, ber, terrain);

        long vertexVk = useOit ? VkContext.buffer(vertexBuffer) : 0L;
        long indexVk = useOit ? VkContext.buffer(indexBuffer) : 0L;
        int width = target.width;
        int height = target.height;
        float far = FrameUniforms.getDepthFar();

        m.warmTextures(mc.getTextureManager());
        if (VkPrograms.bindlessTexturesEnabled()) {
            VkBindlessTable.refresh(mc.getTextureManager());
            VkBindlessTable.setReserved(VkBindlessTable.SLOT_OVERLAY, frame.overlayView(), frame.overlaySampler());
            VkBindlessTable.setReserved(VkBindlessTable.SLOT_LIGHTMAP, frame.lightmapView(), frame.overlaySampler());
            VkBindlessTable.setReserved(VkBindlessTable.SLOT_BLUE_NOISE, frame.blueNoiseView(),
                    frame.blueNoiseSampler());
            if (!insert) {
                VkBindlessTable.setReserved(VkBindlessTable.SLOT_DEPTH_RANGE, frame.depthRangeView(),
                        frame.oitSampler());
                for (int i = 0; i < 4; i++) {
                    VkBindlessTable.setReserved(VkBindlessTable.SLOT_COEFFICIENTS_BASE + i, frame.coefficientViews()[i],
                            frame.oitSampler());
                }
            }
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        dev.engine_room.flywheel.backend.engine.OitFrame nativeFrame = nativeReplay == null ? null
                : new dev.engine_room.flywheel.backend.engine.OitFrame(encoder, useOit ? vertexBuffer : null,
                        useOit ? indexBuffer : null, dynamicTransforms, overlayView, lightmapView,
                        loSampler, oitSampler, noiseSampler, blueNoiseTexView, mc.getTextureManager());
        #if MC >= 26.3
        boolean fused = nativeReplay != null && VkNativeOitPass.fused(m.programs);
        #else
        boolean fused = false;
        #endif
        if (nativeReplay != null) {
            if (insert) nativeReplay.prepareInsert(oitFramebuffer, nativeFrame, fused);
            else nativeReplay.prepareWavelet(nativeFrame);
        }
        #if MC < 26.3
        if (hasFabulous) {
            LayerOitReplay.prepass(encoder, fabulous, oitFramebuffer);
        }
        #endif

        RenderPassDescriptor compositeDescriptor = RenderPassOps.builder(() -> "flywheel:vk/oit/composite")
                                                                .withColorAttachment(colorView)
                                                                .withDepthAttachment(depthView, OptionalDouble.empty())
                                                                .withRenderArea(new RenderPass.RenderArea(0, 0,
                                                                        target.width, target.height))
                                                                .build();

        ChainInputs inputs = new ChainInputs(encoder, frame, replay, vertexVk, indexVk, width, height, useOit,
                colorView, depthView, far, compositeDescriptor, nativeReplay, nativeFrame);
        if (insert) {
            try {
                insertChain.render(inputs, fused);
            } catch (ProgramAvailability.Failure failure) {
                throw failure;
            } catch (BackendUnavailableException failure) {
                throw new ProgramAvailability.Failure(ProgramAvailability.insert(insertMode), failure);
            }
        } else {
            boolean folded = nativeReplay != null ? fused
                    : m.programs.localRead() && (terrain == null || terrain instanceof VkFoldedOitReplay);
            waveletChain.render(inputs, hasAdditive, folded);
        }

        return true;
    }

    void delete() {
        #if MC >= 26.3
        VkNativeOitPass.releaseFramebuffers();
        #endif
        oitFramebuffer.delete();
        if (insertChain != null) {
            insertChain.delete();
            insertChain = null;
        }
    }

    record OitFrame(GpuBufferSlice projection, GpuBufferSlice dynamicTransforms, GpuBufferSlice fog,
                    GpuBufferSlice lights, GpuBuffer globals, GpuBufferSlice renderOriginSlice,
                    TextureManager textureManager, long overlayView, long overlaySampler, long lightmapView,
                    long atlasSampler, long depthRangeView, long blueNoiseView, long blueNoiseSampler,
                    long oitSampler, long[] coefficientViews, long accumulateView) {
        OitFrame withDepthBounds(long view) {
            return new OitFrame(projection, dynamicTransforms, fog, lights, globals, renderOriginSlice, textureManager,
                    overlayView, overlaySampler, lightmapView, atlasSampler, view, blueNoiseView, blueNoiseSampler,
                    oitSampler, coefficientViews, accumulateView);
        }
    }

    record OitReplay(#if MC < 26.3 @Nullable FabulousCaptures fabulous, #endif
                     GpuBufferSlice dynamicTransforms, GpuTextureView lightmapView, GpuTextureView overlayView,
                     GpuTextureView blueNoiseView, GpuSampler loSampler, GpuSampler oitSampler,
                     GpuSampler noiseSampler, @Nullable ChunkSectionsToRender chunks,
                     @Nullable BerTranslucentCapture ber, @Nullable SodiumTerrainOitReplay terrain) {
    }

    /** One frame's chain inputs; {@code hasInstanceOit}: engine instances draw into the producer stages. */
    record ChainInputs(CommandEncoder encoder, OitFrame frame, OitReplay replay, long vertexVk, long indexVk,
                       int width, int height, boolean hasInstanceOit, GpuTextureView colorView,
                       GpuTextureView depthView, float far, RenderPassDescriptor compositeDescriptor,
                       @Nullable NativeOitReplay nativeReplay,
                       dev.engine_room.flywheel.backend.engine.@Nullable OitFrame nativeFrame) {
    }
}
