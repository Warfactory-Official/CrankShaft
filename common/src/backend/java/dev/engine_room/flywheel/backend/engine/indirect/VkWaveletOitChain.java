package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.VkOitPipelines;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.vk.FlwPassBarrier;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.List;

import static dev.engine_room.flywheel.backend.vk.VkCmd.*;

/**
 * The VK wavelet/moment OIT chain: depthRange -> coefficients -> depth-from-transmittance -> evaluate -> composite, folded (local_read) or standalone passes.
 */
final class VkWaveletOitChain extends VkOitChain {
    VkWaveletOitChain(VkIndirectDrawManager m, OitFramebuffer framebuffer) {
        super(m, framebuffer);
    }

    // The wavelet OIT reads: input attachments on the folded instance, samplers on the standalone passes (bindless: reserved slots, nothing pushed).
    static void writeOitReads(VkDescriptorWriter writer, VkOitRenderer.OitFrame f, OitMode mode, boolean folded) {
        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        if (folded) {
            writer.inputAttachment(14, f.depthRangeView());
        } else if (!bindless) {
            writer.sampler(14, f.depthRangeView(), f.oitSampler());
        }
        if (mode == OitMode.EVALUATE) {
            for (int i = 0; i < 4; i++) {
                if (folded) {
                    writer.inputAttachment(24 + i, f.coefficientViews()[i]);
                } else if (!bindless) {
                    writer.sampler(24 + i, f.coefficientViews()[i], f.oitSampler());
                }
            }
        }
    }

    private static void setFoldedLocations(VkCommandBuffer cmd, @Nullable OitMode mode) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderingAttachmentLocationInfoKHR locations = VkRenderingAttachmentLocationInfoKHR.calloc(stack)
                                                                                                 .sType$Default()
                                                                                                 .pColorAttachmentLocations(
                                                                                                         stack.ints(
                                                                                                                 VkOitPipelines.foldedLocations(
                                                                                                                         mode)));
            KHRDynamicRenderingLocalRead.vkCmdSetRenderingAttachmentLocationsKHR(cmd, locations);
        }
    }

    // The only barrier shape legal INSIDE a dynamic rendering instance (local_read): framebuffer-space, memory-only, BY_REGION.
    private static void byRegionBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var b = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack)
                                                    .sType$Default()
                                                    .srcAccessMask(VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                                                    .dstAccessMask(VK12.VK_ACCESS_INPUT_ATTACHMENT_READ_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK12.VK_DEPENDENCY_BY_REGION_BIT, b, null, null);
        }
    }

    void render(CommandEncoder encoder, VkOitRenderer.OitFrame frame, VkOitRenderer.OitReplay replay,
                long vertexVk, long indexVk, int width, int height, boolean hasInstanceOit,
                GpuTextureView depthView, float far, RenderPassDescriptor compositeDescriptor, boolean folded) {
        if (folded) {
            foldedProducers(frame, replay, vertexVk, indexVk, width, height, hasInstanceOit, depthView, far);
        } else {
            producerPass(encoder, framebuffer.depthRangeDescriptor(depthView, far), OitMode.DEPTH_RANGE, frame, replay,
                    vertexVk, indexVk, width, height, hasInstanceOit);
            producerPass(encoder, framebuffer.coefficientsDescriptor(depthView), OitMode.GENERATE_COEFFICIENTS, frame,
                    replay, vertexVk, indexVk, width, height, hasInstanceOit);
            fullscreenPass(encoder, framebuffer.depthFromTransmittanceDescriptor(depthView),
                    m.programs.oit().depthPipeline(false), frame, width, height, false);
            producerPass(encoder, framebuffer.accumulateDescriptor(depthView), OitMode.EVALUATE, frame, replay,
                    vertexVk, indexVk, width, height, hasInstanceOit);
        }
        fullscreenPass(encoder, compositeDescriptor, m.programs.oit().compositePipeline(), frame, width, height, true);
    }

    private void producerPass(CommandEncoder encoder, RenderPassDescriptor descriptor, OitMode mode,
                              VkOitRenderer.OitFrame frame,
                              VkOitRenderer.OitReplay replay, long vertexVk, long indexVk, int width, int height,
                              boolean hasInstanceOit) {
        // This producer's output is SAMPLED by the next OIT pass, so declare the precise submit barrier -> the encoder mixin skips Mojang's ALL_COMMANDS barrier for this close.
        FlwPassBarrier.expectFramebufferSample();
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            VkCommandBuffer cmd = ((VulkanRenderPass) pass.backend).commandBuffer;
            setViewportScissor(cmd, width, height);
            VkContext.pushLabel(cmd, "flywheel:vk/oit/producer/" + mode.name);
            try {
                if (hasInstanceOit) {
                    VK12.vkCmdBindIndexBuffer(cmd, indexVk, 0L, VK12.VK_INDEX_TYPE_UINT32);
                    bindVertexBuffer(cmd, vertexVk);
                    m.drawOitProducerGeometry(cmd, mode, frame, false);
                }

                boolean hasBer = replay.ber() != null && !replay.ber().isEmpty();
                boolean hasFabulous = replay.fabulous() != null && replay.fabulous().hasAny();
                if (replay.chunks() != null || replay.terrain() != null || hasBer || hasFabulous) {
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", replay.dynamicTransforms());
                    if (replay.chunks() != null) {
                        VkContext.pushLabel(cmd, "flywheel:vk/oit/chunk_rhi/" + mode.name);
                        ChunkTranslucentReplay.replay(pass, replay.chunks(), mode, framebuffer,
                                replay.lightmapView(), replay.blueNoiseView(), replay.loSampler(), replay.oitSampler(),
                                replay.noiseSampler());
                        VkContext.popLabel(cmd);
                    }
                    if (replay.terrain() != null) {
                        VkContext.pushLabel(cmd, "flywheel:vk/oit/terrain_rhi/" + mode.name);
                        replay.terrain().replay(pass, mode, framebuffer,
                                replay.lightmapView(), replay.blueNoiseView(), replay.loSampler(), replay.oitSampler(),
                                replay.noiseSampler());
                        VkContext.popLabel(cmd);
                    }
                    if (hasBer) {
                        VkContext.pushLabel(cmd, "flywheel:vk/oit/ber_rhi/" + mode.name);
                        BerTranslucentReplay.replay(pass, replay.ber(), mode, framebuffer,
                                replay.lightmapView(), replay.overlayView(), replay.blueNoiseView(), replay.loSampler(),
                                replay.oitSampler(), replay.noiseSampler());
                        VkContext.popLabel(cmd);
                    }
                    if (hasFabulous) {
                        FabulousCaptures fab = replay.fabulous();
                        if (fab.hasClouds()) {
                            VkContext.pushLabel(cmd, "flywheel:vk/oit/fabulous_rhi/clouds/" + mode.name);
                            LayerOitReplay.replay(pass, framebuffer.cloudsColorView(), framebuffer.cloudsDepthView(),
                                    fab.cloudsTransform, mode, framebuffer, replay.blueNoiseView(), replay.oitSampler(),
                                    replay.noiseSampler());
                            VkContext.popLabel(cmd);
                        }
                        if (fab.hasItemLayer()) {
                            VkContext.pushLabel(cmd, "flywheel:vk/oit/fabulous_rhi/item_layer/" + mode.name);
                            LayerOitReplay.replay(pass, fab.itemLayerColor, fab.itemLayerDepth,
                                    replay.dynamicTransforms(), mode, framebuffer, replay.blueNoiseView(),
                                    replay.oitSampler(), replay.noiseSampler());
                            VkContext.popLabel(cmd);
                        }
                        if (fab.hasParticleLayer()) {
                            VkContext.pushLabel(cmd, "flywheel:vk/oit/fabulous_rhi/particle_layer/" + mode.name);
                            LayerOitReplay.replay(pass, fab.particleLayerColor, fab.particleLayerDepth,
                                    replay.dynamicTransforms(), mode, framebuffer, replay.blueNoiseView(),
                                    replay.oitSampler(), replay.noiseSampler());
                            VkContext.popLabel(cmd);
                        }
                        if (fab.hasWeather()) {
                            VkContext.pushLabel(cmd, "flywheel:vk/oit/fabulous_rhi/weather/" + mode.name);
                            if (OitConfig.exactFabulous()) {
                                WeatherOitReplay.replay(pass, fab, mode, framebuffer,
                                        replay.lightmapView(), replay.blueNoiseView(), replay.loSampler(),
                                        replay.oitSampler(), replay.noiseSampler(),
                                        Minecraft.getInstance().getTextureManager());
                            } else {
                                LayerOitReplay.replay(pass, framebuffer.weatherColorView(),
                                        framebuffer.weatherDepthView(),
                                        replay.dynamicTransforms(), mode, framebuffer, replay.blueNoiseView(),
                                        replay.oitSampler(), replay.noiseSampler());
                            }
                            VkContext.popLabel(cmd);
                        }
                    }
                }
            } finally {
                VkContext.popLabel(cmd);
            }
        } finally {
            FlwPassBarrier.clear();
        }
    }

    private void fullscreenPass(CommandEncoder encoder, RenderPassDescriptor descriptor, VkGraphicsPipeline pipeline,
                                VkOitRenderer.OitFrame frame, int width, int height, boolean composite) {
        if (composite) {
            FlwPassBarrier.expectFramebufferProducer();
        } else {
            FlwPassBarrier.expectFramebufferSample();
        }
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            VkCommandBuffer cmd = ((VulkanRenderPass) pass.backend).commandBuffer;
            setViewportScissor(cmd, width, height);
            VkContext.pushLabel(cmd, composite ? "flywheel:vk/oit/composite" : "flywheel:vk/oit/transmittance_depth");
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            if (composite) {
                m.writer.sampler(28, frame.accumulateView(), frame.oitSampler());
            }
            m.writer.sampler(14, frame.depthRangeView(), frame.oitSampler());
            for (int i = 0; i < 4; i++) {
                m.writer.sampler(24 + i, frame.coefficientViews()[i], frame.oitSampler());
            }
            m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            VK12.vkCmdDraw(cmd, 3, 1, 0, 0);
            VkContext.popLabel(cmd);
        } finally {
            FlwPassBarrier.clear();
        }
    }

    private void foldedProducers(VkOitRenderer.OitFrame frame, VkOitRenderer.OitReplay replay, long vertexVk,
                                 long indexVk,
                                 int width, int height, boolean hasInstanceOit, GpuTextureView depthView, float far) {
        VkCommandBuffer cmd = VkContext.beginCommands();
        VkContext.pushLabel(cmd, "flywheel:vk/oit/folded");
        memoryBarrier(cmd,
                VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK12.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                        | VK12.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] views = {frame.depthRangeView(), frame.coefficientViews()[0], frame.coefficientViews()[1],
                    frame.coefficientViews()[2], frame.coefficientViews()[3], frame.accumulateView()};
            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(6, stack);
            for (int i = 0; i < 6; i++) {
                VkClearValue clear = VkClearValue.calloc(stack);
                clear.color().float32(0, i == 0 ? -far : 0.0f).float32(1, i == 0 ? -far : 0.0f).float32(2, 0.0f)
                     .float32(3, 0.0f);
                color.get(i)
                     .sType$Default()
                     .imageView(views[i])
                     .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                     .loadOp(VK12.VK_ATTACHMENT_LOAD_OP_CLEAR)
                     .storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE)
                     .clearValue(clear);
            }
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack)
                                                                       .sType$Default()
                                                                       .imageView(VkContext.imageView(depthView))
                                                                       .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                                                                       .loadOp(VK12.VK_ATTACHMENT_LOAD_OP_LOAD)
                                                                       .storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default();
            rendering.renderArea().extent().set(width, height);
            rendering.layerCount(1)
                     .pColorAttachments(color)
                     .pDepthAttachment(depth);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, rendering);

            VkRenderingInputAttachmentIndexInfoKHR inputIndices = VkRenderingInputAttachmentIndexInfoKHR.calloc(stack)
                                                                                                        .sType$Default()
                                                                                                        .pColorAttachmentInputIndices(
                                                                                                                stack.ints(
                                                                                                                        VkOitPipelines.FOLDED_INPUT_INDICES));
            KHRDynamicRenderingLocalRead.vkCmdSetRenderingInputAttachmentIndicesKHR(cmd, inputIndices);
        }
        setViewportScissor(cmd, width, height);

        VkContext.pushLabel(cmd, "flywheel:vk/oit/folded/stage/depth_range");
        foldedStage(cmd, OitMode.DEPTH_RANGE, frame, replay, hasInstanceOit, vertexVk, indexVk);
        VkContext.popLabel(cmd);
        byRegionBarrier(cmd);
        VkContext.pushLabel(cmd, "flywheel:vk/oit/folded/stage/coefficients");
        foldedStage(cmd, OitMode.GENERATE_COEFFICIENTS, frame, replay, hasInstanceOit, vertexVk, indexVk);
        VkContext.popLabel(cmd);
        byRegionBarrier(cmd);

        // Transmittance-depth: fullscreen, writes only gl_FragDepth; EVALUATE's test against it is rasterization-ordered (no barrier).
        VkContext.pushLabel(cmd, "flywheel:vk/oit/folded/stage/transmittance_depth");
        setFoldedLocations(cmd, null);
        VkGraphicsPipeline depthPipe = m.programs.oit().depthPipeline(true);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, depthPipe.handle());
        m.writer.inputAttachment(14, frame.depthRangeView());
        for (int i = 0; i < 4; i++) {
            m.writer.inputAttachment(24 + i, frame.coefficientViews()[i]);
        }
        m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, depthPipe.layout());
        VK12.vkCmdDraw(cmd, 3, 1, 0, 0);
        VkContext.popLabel(cmd);

        VkContext.pushLabel(cmd, "flywheel:vk/oit/folded/stage/evaluate");
        foldedStage(cmd, OitMode.EVALUATE, frame, replay, hasInstanceOit, vertexVk, indexVk);
        VkContext.popLabel(cmd);

        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        memoryBarrier(cmd,
                VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK12.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        VkContext.popLabel(cmd);
        VkContext.submitCommands(cmd);
    }

    private void foldedStage(VkCommandBuffer cmd, OitMode mode, VkOitRenderer.OitFrame frame,
                             VkOitRenderer.OitReplay replay,
                             boolean hasInstanceOit, long vertexVk, long indexVk) {
        setFoldedLocations(cmd, mode);
        if (hasInstanceOit) {
            VK12.vkCmdBindIndexBuffer(cmd, indexVk, 0L, VK12.VK_INDEX_TYPE_UINT32);
            bindVertexBuffer(cmd, vertexVk);
            m.drawOitProducerGeometry(cmd, mode, frame, true);
        }
        if (replay.terrain() instanceof VkFoldedOitReplay terrain) {
            terrain.replayFolded(cmd, mode, framebuffer, replay.lightmapView(), replay.blueNoiseView(),
                    replay.loSampler(), replay.oitSampler(), replay.noiseSampler());
        }
        if (replay.chunks() != null) {
            foldedChunks(cmd, mode, frame, replay.chunks());
        }
        if (replay.ber() != null && !replay.ber().isEmpty()) {
            foldedBer(cmd, mode, frame, replay.ber());
        }
        if (replay.fabulous() != null && replay.fabulous().hasAny()) {
            foldedFabulous(cmd, mode, frame, replay.fabulous());
        }
    }

    private void writeFoldedExtraReads(OitMode mode, VkOitRenderer.OitFrame frame) {
        m.writer.inputAttachment(14, frame.depthRangeView());
        m.writer.sampler(15, frame.blueNoiseView(), frame.blueNoiseSampler());
        if (mode == OitMode.EVALUATE) {
            for (int i = 0; i < 4; i++) {
                m.writer.inputAttachment(24 + i, frame.coefficientViews()[i]);
            }
        }
    }

    private void foldedChunks(VkCommandBuffer cmd, OitMode mode, VkOitRenderer.OitFrame frame,
                              net.minecraft.client.renderer.chunk.ChunkSectionsToRender chunks) {
        var drawGroup = chunks.drawGroupsPerLayer().get(ChunkSectionLayer.TRANSLUCENT);
        if (drawGroup == null || drawGroup.isEmpty()) {
            return;
        }
        VkContext.pushLabel(cmd, "flywheel:vk/oit/chunk/" + mode.name);
        VkGraphicsPipeline pipeline = m.programs.oit().chunkFoldedPipeline(mode);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        boolean nonDepthRange = mode != OitMode.DEPTH_RANGE;
        long atlasView = VkContext.imageView(chunks.textureView());
        long sharedIndexBuffer = chunkSharedIndexBuffer(chunks);
        for (var draws : drawGroup.values()) {
            for (RenderPass.Draw<com.mojang.blaze3d.buffers.GpuBufferSlice[]> draw : draws.reversed()) {
                bindChunkDraw(cmd, frame, chunks, atlasView, sharedIndexBuffer, draw);
                if (nonDepthRange) {
                    writeFoldedExtraReads(mode, frame);
                }
                m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
                VK12.vkCmdDrawIndexed(cmd, draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
            }
        }
        VkContext.popLabel(cmd);
    }

    private void foldedBer(VkCommandBuffer cmd, OitMode mode, VkOitRenderer.OitFrame frame, BerTranslucentCapture ber) {
        VkContext.pushLabel(cmd, "flywheel:vk/oit/ber/" + mode.name);
        boolean nonDepthRange = mode != OitMode.DEPTH_RANGE;
        for (BerFamily family : BerFamily.VALUES) {
            List<BerTranslucentCapture.CapturedDraw> draws = ber.draws(family);
            if (draws.isEmpty()) {
                continue;
            }
            VkGraphicsPipeline pipeline = m.programs.oit().berFoldedPipeline(family, mode);
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            for (BerTranslucentCapture.CapturedDraw draw : draws) {
                bindBerDraw(cmd, frame, family, draw);
                if (nonDepthRange) {
                    writeFoldedExtraReads(mode, frame);
                }
                m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
                StagedVertexBuffer.ExecuteInfo info = draw.info();
                VK12.vkCmdDrawIndexed(cmd, info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
            }
        }
        VkContext.popLabel(cmd);
    }

    private void foldedFabulous(VkCommandBuffer cmd, OitMode mode, VkOitRenderer.OitFrame frame, FabulousCaptures fab) {
        VkContext.pushLabel(cmd, "flywheel:vk/oit/fabulous/" + mode.name);
        boolean nonDepthRange = mode != OitMode.DEPTH_RANGE;
        if (fab.hasClouds() || fab.hasItemLayer() || fab.hasParticleLayer()) {
            VkGraphicsPipeline pipeline = m.programs.oit().layerFoldedPipeline(mode);
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            if (fab.hasClouds()) {
                drawFoldedLayer(cmd, mode, frame, pipeline, nonDepthRange,
                        VkContext.imageView(framebuffer.cloudsColorView()),
                        VkContext.imageView(framebuffer.cloudsDepthView()));
            }
            if (fab.hasItemLayer()) {
                drawFoldedLayer(cmd, mode, frame, pipeline, nonDepthRange,
                        VkContext.imageView(fab.itemLayerColor),
                        VkContext.imageView(fab.itemLayerDepth));
            }
            if (fab.hasParticleLayer()) {
                drawFoldedLayer(cmd, mode, frame, pipeline, nonDepthRange,
                        VkContext.imageView(fab.particleLayerColor),
                        VkContext.imageView(fab.particleLayerDepth));
            }
        }
        if (fab.hasWeather()) {
            if (!OitConfig.exactFabulous()) {
                VkGraphicsPipeline pipeline = m.programs.oit().layerFoldedPipeline(mode);
                VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
                drawFoldedLayer(cmd, mode, frame, pipeline, nonDepthRange,
                        VkContext.imageView(framebuffer.weatherColorView()),
                        VkContext.imageView(framebuffer.weatherDepthView()));
            } else {
                VkGraphicsPipeline pipeline = m.programs.oit().weatherFoldedPipeline(mode);
                VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
                bindVertexBuffer(cmd, VkContext.buffer(fab.weatherVertices));
                RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
                GpuBuffer indexGpu = indices.getBuffer(6 * (fab.rainColumns + fab.snowColumns));
                int indexType = indices.type() == IndexType.SHORT ? VK12.VK_INDEX_TYPE_UINT16 : VK12.VK_INDEX_TYPE_UINT32;
                VK12.vkCmdBindIndexBuffer(cmd, VkContext.buffer(indexGpu), 0L, indexType);

                TextureManager textureManager = frame.textureManager();
                drawFoldedWeatherRange(cmd, mode, frame, fab, pipeline,
                        textureManager.getTexture(WeatherOitReplay.RAIN_LOCATION), 0, fab.rainColumns, nonDepthRange);
                drawFoldedWeatherRange(cmd, mode, frame, fab, pipeline,
                        textureManager.getTexture(WeatherOitReplay.SNOW_LOCATION), fab.rainColumns, fab.snowColumns,
                        nonDepthRange);
            }
        }
        VkContext.popLabel(cmd);
    }

    private void drawFoldedLayer(VkCommandBuffer cmd, OitMode mode, VkOitRenderer.OitFrame frame,
                                 VkGraphicsPipeline pipeline,
                                 boolean nonDepthRange, long colorView, long depthView) {
        m.writer.sampler(29, colorView, frame.oitSampler());
        m.writer.sampler(30, depthView, frame.oitSampler());
        if (nonDepthRange) {
            writeFoldedExtraReads(mode, frame);
        }
        m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
        VK12.vkCmdDraw(cmd, 3, 1, 0, 0);
    }

    private void drawFoldedWeatherRange(VkCommandBuffer cmd, OitMode mode, VkOitRenderer.OitFrame frame,
                                        FabulousCaptures fab,
                                        VkGraphicsPipeline pipeline, AbstractTexture texture, int startColumn,
                                        int columnCount, boolean nonDepthRange) {
        if (columnCount == 0) {
            return;
        }
        m.writer.sampler(10, VkContext.imageView(texture.getTextureView()), VkContext.sampler(texture.getSampler()))
                .sampler(12, frame.lightmapView(), frame.overlaySampler())
                .uniform(16, frame.projection())
                .uniform(17, fab.weatherTransform)
                .uniform(18, frame.fog());
        if (nonDepthRange) {
            writeFoldedExtraReads(mode, frame);
        }
        m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
        VK12.vkCmdDrawIndexed(cmd, columnCount * 6, 1, startColumn * 6, 0, 0);
    }
}
