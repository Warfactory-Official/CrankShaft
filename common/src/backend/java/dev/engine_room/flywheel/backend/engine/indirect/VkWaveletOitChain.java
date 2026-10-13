package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.systems.RenderSystem;
#if MC < 26.3
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.engine_room.flywheel.backend.OitConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
#endif
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
#if MC >= 26.3
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
#endif
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.VkOitPipelines;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.engine.*;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.engine.ChunkReplayData;
#endif
import dev.engine_room.flywheel.backend.vk.FlwPassBarrier;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.List;

import static dev.engine_room.flywheel.backend.vk.VkCmd.*;

final class VkWaveletOitChain extends VkOitChain {
    VkWaveletOitChain(VkIndirectDrawManager m, OitFramebuffer framebuffer) {
        super(m, framebuffer);
    }

    static void writeOitReads(VkDescriptorWriter writer, VkOitRenderer.OitFrame f, OitMode mode, boolean folded) {
        boolean bindless = VkPrograms.bindlessTexturesEnabled();
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

    private static void setFoldedLocations(VkCommandBuffer cmd, OitMode mode) {
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

    private static void byRegionBarrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var b = VkMemoryBarrier.calloc(1, stack)
                                                    .sType$Default()
                                                    .srcAccessMask(VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                                                    .dstAccessMask(VK12.VK_ACCESS_INPUT_ATTACHMENT_READ_BIT);
            VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK12.VK_DEPENDENCY_BY_REGION_BIT, b, null, null);
        }
    }

    void render(VkOitRenderer.ChainInputs in, boolean hasAdditive, boolean folded) {
        VkOitRenderer.OitFrame frame = in.frame();
        NativeOitReplay nativeReplay = in.nativeReplay();
        if (folded && nativeReplay != null) {
            frame = nativeFoldedProducers(in, nativeReplay);
        } else if (folded) {
            foldedProducers(in);
        } else {
            RenderPassDescriptor depthRange = framebuffer.depthRangeDescriptor(in.depthView(), in.far());
            producerPass(in, depthRange, OitMode.DEPTH_RANGE, frame);
            if (nativeReplay != null) {
                nativeReplay.replayWavelet(depthRange, OitMode.DEPTH_RANGE, false, framebuffer, in.nativeFrame());
                frame = withCulledBounds(frame);
            }
            RenderPassDescriptor coefficients = framebuffer.coefficientsDescriptor(in.depthView());
            producerPass(in, coefficients, OitMode.GENERATE_COEFFICIENTS, frame);
            if (nativeReplay != null) {
                nativeReplay.replayWavelet(coefficients, OitMode.GENERATE_COEFFICIENTS, false, framebuffer,
                        in.nativeFrame());
            }
            RenderPassDescriptor accumulate = framebuffer.accumulateDescriptor(in.depthView());
            producerPass(in, accumulate, OitMode.EVALUATE, frame);
            if (nativeReplay != null) {
                nativeReplay.replayWavelet(accumulate, OitMode.EVALUATE, false, framebuffer, in.nativeFrame());
            }
        }
        if (nativeReplay != null) {
            hasAdditive |= nativeReplay.hasAdditive();
        }
        if (hasAdditive) {
            framebuffer.prepareEmission();
            emissionPass(in, framebuffer.emissionDescriptor(in.depthView()), frame, folded);
        }
        compositePass(in.encoder(), in.compositeDescriptor(), m.programs.oit().compositePipeline(hasAdditive), frame,
                in.width(), in.height(), hasAdditive);
        if (hasAdditive) {
            emissionComposite(in.encoder(), in.compositeDescriptor(), frame, in.width(), in.height());
        }
    }

    private void emissionPass(VkOitRenderer.ChainInputs in, RenderPassDescriptor descriptor,
                              VkOitRenderer.OitFrame frame, boolean fused) {
        NativeOitReplay nativeReplay = in.nativeReplay();
        #if MC >= 26.3
        if (fused && nativeReplay != null) {
            var targets = nativeReplay.vulkanTargets(framebuffer, false);
            GpuTextureView mainDepth = targets.mainDepth();
            VkNativeOitPass.getEmission(VulkanConst.toVk(mainDepth.texture().getFormat())).record(targets,
                    nativeReplay.vulkanBackendFactory(), false, mainDepth.getWidth(0), mainDepth.getHeight(0), 0.0f,
                    owner -> {
                        if (in.hasInstanceOit() && owner.phase() == VkNativeOitPass.Phase.EMISSION_MAIN) {
                            bindInstances(owner.commandBuffer(), in.vertexVk(), in.indexVk());
                            m.drawOitProducerGeometry(owner.commandBuffer(), OitMode.EVALUATE, frame, false, true);
                        }
                        nativeReplay.replayVulkanCategory(owner, null, null, framebuffer, in.nativeFrame());
                    });
            return;
        }
        #endif
        FlwPassBarrier.expectFramebufferSample();
        try (RenderPass pass = in.encoder().createRenderPass(descriptor)) {
            VkCommandBuffer cmd = VkContext.renderPass(pass).commandBuffer;
            setViewportScissor(cmd, in.width(), in.height());
            VkContext.pushLabel(cmd, "flywheel:vk/oit/producer/emission");
            if (in.hasInstanceOit()) {
                bindInstances(cmd, in.vertexVk(), in.indexVk());
                m.drawOitProducerGeometry(cmd, OitMode.EVALUATE, frame, false, true);
            }
            VkContext.popLabel(cmd);
        } finally {
            FlwPassBarrier.clear();
        }
        if (nativeReplay != null) {
            nativeReplay.replayWavelet(descriptor, OitMode.EVALUATE, true, framebuffer, in.nativeFrame());
        }
    }

    private void emissionComposite(CommandEncoder encoder, RenderPassDescriptor descriptor,
                                   VkOitRenderer.OitFrame frame, int width, int height) {
        FlwPassBarrier.expectFramebufferProducer();
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            VkCommandBuffer cmd = VkContext.renderPass(pass).commandBuffer;
            setViewportScissor(cmd, width, height);
            VkContext.pushLabel(cmd, "flywheel:vk/oit/emission_composite");
            VkGraphicsPipeline pipeline = m.programs.oit().emissionPipeline();
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            m.writer.sampler(28, frame.accumulateView(), frame.oitSampler());
            m.writer.sampler(38, VkContext.imageView(framebuffer.emissionView()), frame.oitSampler());
            m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            VK12.vkCmdDraw(cmd, 3, 1, 0, 0);
            VkContext.popLabel(cmd);
        } finally {
            FlwPassBarrier.clear();
        }
    }

    private void producerPass(VkOitRenderer.ChainInputs in, RenderPassDescriptor descriptor, OitMode mode,
                              VkOitRenderer.OitFrame frame) {
        VkOitRenderer.OitReplay replay = in.replay();
        FlwPassBarrier.expectFramebufferSample();
        try (RenderPass pass = in.encoder().createRenderPass(descriptor)) {
            VkCommandBuffer cmd = VkContext.renderPass(pass).commandBuffer;
            setViewportScissor(cmd, in.width(), in.height());
            VkContext.pushLabel(cmd, "flywheel:vk/oit/producer/" + mode.name);
            try {
                if (in.hasInstanceOit()) {
                    bindInstances(cmd, in.vertexVk(), in.indexVk());
                    m.drawOitProducerGeometry(cmd, mode, frame, false, false);
                }

                boolean hasBer = replay.ber() != null && !replay.ber().isEmpty();
                boolean replays = replay.chunks() != null || replay.terrain() != null || hasBer;
                #if MC < 26.3
                boolean hasFabulous = replay.fabulous() != null && replay.fabulous().hasAny();
                replays |= hasFabulous;
                #endif
                if (replays) {
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
                    #if MC < 26.3
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
                    #endif
                }
            } finally {
                VkContext.popLabel(cmd);
            }
        } finally {
            FlwPassBarrier.clear();
        }
    }

    private void compositePass(CommandEncoder encoder, RenderPassDescriptor descriptor, VkGraphicsPipeline pipeline,
                               VkOitRenderer.OitFrame frame, int width, int height, boolean emission) {
        FlwPassBarrier.expectFramebufferProducer();
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            VkCommandBuffer cmd = VkContext.renderPass(pass).commandBuffer;
            setViewportScissor(cmd, width, height);
            VkContext.pushLabel(cmd, "flywheel:vk/oit/composite");
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            m.writer.sampler(28, frame.accumulateView(), frame.oitSampler());
            if (emission) {
                m.writer.sampler(38, VkContext.imageView(framebuffer.emissionView()), frame.oitSampler());
            }
            m.writer.sampler(14, frame.depthRangeView(), frame.oitSampler());
            for (int i = 0; i < 4; i++) {
                m.writer.sampler(24 + i, frame.coefficientViews()[i], frame.oitSampler());
            }
            m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            VK12.vkCmdDraw(cmd, 3, 1, 0, 0);
            VkGraphicsPipeline depth = m.programs.oit().oitDepthPipeline();
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, depth.handle());
            m.writer.sampler(28, frame.accumulateView(), frame.oitSampler());
            m.writer.sampler(14, frame.depthRangeView(), frame.oitSampler());
            m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, depth.layout());
            VK12.vkCmdDraw(cmd, 3, 1, 0, 0);
            VkContext.popLabel(cmd);
        } finally {
            FlwPassBarrier.clear();
        }
    }

    private void foldedProducers(VkOitRenderer.ChainInputs in) {
        VkOitRenderer.OitFrame frame = in.frame();
        int width = in.width();
        int height = in.height();
        float far = in.far();
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
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default();
            rendering.renderArea().extent().set(width, height);
            rendering.layerCount(1)
                     .pColorAttachments(color);
            VkContext.attachDepth(stack, rendering, in.depthView());
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
        foldedStage(cmd, OitMode.DEPTH_RANGE, in);
        VkContext.popLabel(cmd);
        byRegionBarrier(cmd);
        VkContext.pushLabel(cmd, "flywheel:vk/oit/folded/stage/coefficients");
        foldedStage(cmd, OitMode.GENERATE_COEFFICIENTS, in);
        VkContext.popLabel(cmd);
        byRegionBarrier(cmd);
        VkContext.pushLabel(cmd, "flywheel:vk/oit/folded/stage/evaluate");
        foldedStage(cmd, OitMode.EVALUATE, in);
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

    private VkOitRenderer.OitFrame nativeFoldedProducers(VkOitRenderer.ChainInputs in, NativeOitReplay replay) {
        #if MC >= 26.3
        var targets = replay.vulkanTargets(framebuffer, false);
        VkOitRenderer.OitFrame[] frame = {in.frame()};
        VkNativeOitPass.get(VkNativeOitPass.spec(targets, false)).record(targets, replay.vulkanBackendFactory(), false,
                in.width(), in.height(), in.far(), owner -> {
                    var phase = owner.phase();
                    if (in.hasInstanceOit() && phase.main()) {
                        bindInstances(owner.commandBuffer(), in.vertexVk(), in.indexVk());
                        m.drawOitProducerGeometry(owner.commandBuffer(), phase.mode(), frame[0], true, false);
                    }
                    replay.replayVulkanCategory(owner, null, null, framebuffer, in.nativeFrame());
                    if (phase == VkNativeOitPass.Phase.CULL_BOUNDS) frame[0] = withCulledBounds(frame[0]);
                });
        return frame[0];
        #else
        throw new UnsupportedOperationException("Native OIT categories require Minecraft 26.3");
        #endif
    }

    private VkOitRenderer.OitFrame withCulledBounds(VkOitRenderer.OitFrame frame) {
        VkOitRenderer.OitFrame culled = frame.withDepthBounds(VkContext.imageView(framebuffer.depthBoundsView()));
        if (VkPrograms.bindlessTexturesEnabled()) {
            VkBindlessTable.setReserved(VkBindlessTable.SLOT_DEPTH_RANGE, culled.depthRangeView(), culled.oitSampler());
        }
        return culled;
    }

    private void foldedStage(VkCommandBuffer cmd, OitMode mode, VkOitRenderer.ChainInputs in) {
        VkOitRenderer.OitFrame frame = in.frame();
        VkOitRenderer.OitReplay replay = in.replay();
        setFoldedLocations(cmd, mode);
        if (in.hasInstanceOit()) {
            bindInstances(cmd, in.vertexVk(), in.indexVk());
            m.drawOitProducerGeometry(cmd, mode, frame, true, false);
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
        #if MC < 26.3
        if (replay.fabulous() != null && replay.fabulous().hasAny()) {
            foldedFabulous(cmd, mode, frame, replay.fabulous());
        }
        #endif
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
                              ChunkSectionsToRender chunks) {
        #if MC >= 26.3
        if (chunks instanceof ChunkSectionsToRender.DrawIndirect indirect) {
            if (indirect.drawGroupsPerLayer.get(ChunkSectionLayer.TRANSLUCENT).isEmpty()) return;
            VkContext.pushLabel(cmd, "flywheel:vk/oit/chunk_indirect/" + mode.name);
            VkGraphicsPipeline pipeline = m.programs.oit().chunkFoldedPipeline(mode, true);
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            writeIndirectChunkUniforms(frame, indirect, VkContext.imageView(ChunkReplayData.textureView()));
            if (mode != OitMode.DEPTH_RANGE) writeFoldedExtraReads(mode, frame);
            m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            drawIndirectChunks(cmd, indirect, chunkSharedIndexBuffer(chunks));
            VkContext.popLabel(cmd);
            return;
        }
        var draws = ChunkReplayData.separateDraws(chunks);
        if (draws.isEmpty()) return;
        VkContext.pushLabel(cmd, "flywheel:vk/oit/chunk/" + mode.name);
        VkGraphicsPipeline pipeline = m.programs.oit().chunkFoldedPipeline(mode);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        boolean nonDepthRange = mode != OitMode.DEPTH_RANGE;
        long atlasView = VkContext.imageView(ChunkReplayData.textureView());
        long sharedIndexBuffer = chunkSharedIndexBuffer(chunks);
        for (var draw : draws) {
            bindChunkDraw(cmd, frame, chunks, atlasView, sharedIndexBuffer, draw);
            if (nonDepthRange) writeFoldedExtraReads(mode, frame);
            m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            VK12.vkCmdDrawIndexed(cmd, draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
        }
        VkContext.popLabel(cmd);
        #else
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
            for (RenderPass.Draw<GpuBufferSlice[]> draw : draws.reversed()) {
                bindChunkDraw(cmd, frame, chunks, atlasView, sharedIndexBuffer, draw);
                if (nonDepthRange) {
                    writeFoldedExtraReads(mode, frame);
                }
                m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
                VK12.vkCmdDrawIndexed(cmd, draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
            }
        }
        VkContext.popLabel(cmd);
        #endif
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

    #if MC < 26.3
    private void foldedFabulous(VkCommandBuffer cmd, OitMode mode, VkOitRenderer.OitFrame frame, FabulousCaptures fab) {
        VkContext.pushLabel(cmd, "flywheel:vk/oit/fabulous/" + mode.name);
        boolean nonDepthRange = mode != OitMode.DEPTH_RANGE;
        if (fab.hasClouds() || fab.hasItemLayer() || fab.hasParticleLayer()) {
            VkGraphicsPipeline pipeline = m.programs.oit().fabulous().layerFoldedPipeline(mode);
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
                VkGraphicsPipeline pipeline = m.programs.oit().fabulous().layerFoldedPipeline(mode);
                VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
                drawFoldedLayer(cmd, mode, frame, pipeline, nonDepthRange,
                        VkContext.imageView(framebuffer.weatherColorView()),
                        VkContext.imageView(framebuffer.weatherDepthView()));
            } else {
                VkGraphicsPipeline pipeline = m.programs.oit().fabulous().weatherFoldedPipeline(mode);
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
    #endif
}
