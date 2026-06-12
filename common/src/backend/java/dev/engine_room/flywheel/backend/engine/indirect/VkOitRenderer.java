package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
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
                   @Nullable SodiumTerrainOitReplay terrain, @Nullable FabulousCaptures fabulous) {
        boolean useOit = !m.uberOitMultiDraws.isEmpty();
        boolean hasBer = ber != null && !ber.isEmpty();
        boolean hasFabulous = fabulous != null && fabulous.hasAny();
        if (!useOit && chunks == null && !hasBer && terrain == null && !hasFabulous) {
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
        RenderTarget translucentTarget = mc.levelRenderer.translucentTarget();
        RenderTarget target = translucentTarget != null ? translucentTarget : mc.gameRenderer.mainRenderTarget();
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
            terrain.prepareCull(depthView, target.width, target.height);
        }

        boolean canInsert = terrain == null || terrain instanceof VkFoldedOitReplay;
        OitInsertMode insertMode = canInsert ? OitConfig.resolveInsertMode() : null;
        boolean insert = insertMode != null;
        if (!insert) {
            oitFramebuffer.prepare();
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
            if (hasFabulous) {
                oitFramebuffer.prepareLayersOnly();
            }
        }
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                                                       .writeTransform(new Matrix4f(m.renderModelView));

        GpuSampler atlasSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true);
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
        OitReplay replay = new OitReplay(dynamicTransforms, lightmapView, overlayView, blueNoiseTexView,
                loSampler, oitSampler, noiseSampler, chunks, ber, terrain, fabulous);

        long vertexVk = useOit ? VkContext.buffer(vertexBuffer) : 0L;
        long indexVk = useOit ? VkContext.buffer(indexBuffer) : 0L;
        // Size per-pixel OIT storage from the TARGET, never the window: on a resize frame a composite area past _flw_mlabSize walks the pixel-indexed buffers off the end.
        int width = target.width;
        int height = target.height;
        float far = FrameUniforms.getDepthFar();

        m.warmTextures(mc.getTextureManager());
        if (VkCaps.BINDLESS_TEXTURES_NEGOTIATED) {
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
        if (hasFabulous) {
            CloudsOitReplay.prepass(encoder, fabulous, oitFramebuffer);
            if (!OitConfig.exactFabulous()) {
                WeatherOitReplay.prepass(encoder, fabulous, oitFramebuffer);
            }
        }

        RenderPassDescriptor compositeDescriptor = RenderPassDescriptor.create(() -> "flywheel:vk/oit/composite")
                                                                       .withColorAttachment(colorView)
                                                                       .withDepthAttachment(depthView,
                                                                               OptionalDouble.empty())
                                                                       .withRenderArea(new RenderPass.RenderArea(0, 0,
                                                                               target.width, target.height));

        if (insert) {
            insertChain.render(encoder, frame, chunks, ber, terrain, fabulous, lightmapView, loSampler,
                    vertexVk, indexVk, width, height, useOit, depthView, compositeDescriptor);
        } else {
            boolean folded = VkCaps.DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED
                    && (terrain == null || terrain instanceof VkFoldedOitReplay);
            waveletChain.render(encoder, frame, replay, vertexVk, indexVk, width, height, useOit,
                    depthView, far, compositeDescriptor, folded);
        }

        return true;
    }

    void delete() {
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
    }

    // Backend-neutral Mojang-RHI handles for the replays (distinct from OitFrame's raw-VK handles).
    record OitReplay(GpuBufferSlice dynamicTransforms, GpuTextureView lightmapView, GpuTextureView overlayView,
                     GpuTextureView blueNoiseView, GpuSampler loSampler, GpuSampler oitSampler,
                     GpuSampler noiseSampler, @Nullable ChunkSectionsToRender chunks,
                     @Nullable BerTranslucentCapture ber, @Nullable SodiumTerrainOitReplay terrain,
                     @Nullable FabulousCaptures fabulous) {
    }
}
