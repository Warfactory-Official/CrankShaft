package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.vk.FlwPassBarrier;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.util.List;

import static dev.engine_room.flywheel.backend.vk.VkCmd.*;

abstract class VkInsertOitChain extends VkOitChain {
    final OitInsertMode mode;

    @Nullable
    VkBuffer countOrHead;
    @Nullable
    VkBuffer ubo;

    VkInsertOitChain(VkIndirectDrawManager m, OitFramebuffer framebuffer, OitInsertMode mode) {
        super(m, framebuffer);
        this.mode = mode;
    }

    abstract void ensurePayload(long pixels, int layers, int maxNodes);

    abstract void releasePayload();

    abstract VkMlabBuffers buffers();

    abstract int headClearValue();

    void recordClearExtra(VkCommandBuffer cmd) {
    }

    int maxNodes(long pixels) {
        return 0;
    }

    void render(CommandEncoder encoder, VkOitRenderer.OitFrame frame, @Nullable ChunkSectionsToRender chunks,
                @Nullable BerTranslucentCapture ber, @Nullable SodiumTerrainOitReplay terrain,
                @Nullable FabulousCaptures fabulous, GpuTextureView lightmapView, GpuSampler clampLinear,
                long vertexVk, long indexVk, int width, int height, boolean hasInstanceOit,
                GpuTextureView depthView, RenderPassDescriptor compositeDescriptor) {
        VkMlabBuffers mlab = ensureStorage(width, height, fabulous);
        producers(frame, chunks, ber, terrain, fabulous, lightmapView, clampLinear, vertexVk, indexVk,
                width, height, hasInstanceOit, depthView, mlab);
        resolve(encoder, compositeDescriptor, width, height, frame, fabulous);
    }

    private VkMlabBuffers ensureStorage(int width, int height, @Nullable FabulousCaptures fabulous) {
        long pixels = (long) width * height;
        int nodes = maxNodes(pixels);
        int layers = OitConfig.layersFor(mode); // runtime K (sample budget / A-buffer resolve cap)
        int storage = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (ubo == null) {
            ubo = new VkBuffer(VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, 32L); // std140 _FlwMlabUniforms (20B used)
        }
        if (countOrHead == null) {
            countOrHead = new VkBuffer(storage, pixels * Integer.BYTES, true);
        } else {
            countOrHead.ensureCapacity(pixels * Integer.BYTES);
        }
        ensurePayload(pixels, layers, nodes);

        int layerMask = fabulous != null ? fabulous.layerMask(!OitConfig.exactFabulous()) : 0;
        long uboPtr = ubo.mappedAddress();
        MemoryUtil.memPutInt(uboPtr, width);
        MemoryUtil.memPutInt(uboPtr + 4L, height);
        MemoryUtil.memPutInt(uboPtr + 8L, nodes);
        MemoryUtil.memPutInt(uboPtr + 12L, layers);
        MemoryUtil.memPutInt(uboPtr + 16L, layerMask);
        return buffers();
    }

    private void producers(VkOitRenderer.OitFrame frame, @Nullable ChunkSectionsToRender chunks,
                           @Nullable BerTranslucentCapture ber, @Nullable SodiumTerrainOitReplay terrain,
                           @Nullable FabulousCaptures fabulous, GpuTextureView lightmapView, GpuSampler clampLinear,
                           long vertexVk, long indexVk, int width, int height, boolean hasInstanceOit,
                           GpuTextureView depthView, VkMlabBuffers mlab) {
        long pixels = (long) width * height;

        VkCommandBuffer cmd = VkContext.beginCommands();
        VkContext.pushLabel(cmd, "flywheel:vk/oit/" + mode);
        // srcAccess MUST carry SHADER_WRITE (70caa187): last frame's producer SSBO atomics are fragment-stage
        // writes the transfer clear below overwrites (WAW) -- without it stale heads survive the clear and the
        // resolve walk goes unbounded.
        VkContext.pushLabel(cmd, "flywheel:vk/oit/mlab/barrier");
        memoryBarrier(cmd,
                VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK12.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT | VK12.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK12.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
        VkContext.popLabel(cmd);
        VkContext.pushLabel(cmd, "flywheel:vk/oit/mlab/clear");
        VK12.vkCmdFillBuffer(cmd, countOrHead.vkBuffer(), 0L, pixels * Integer.BYTES, headClearValue());
        recordClearExtra(cmd);
        memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
        VkContext.popLabel(cmd);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack)
                                                                       .sType$Default()
                                                                       .imageView(VkContext.imageView(depthView))
                                                                       .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                                                                       .loadOp(VK12.VK_ATTACHMENT_LOAD_OP_LOAD)
                                                                       .storeOp(VK12.VK_ATTACHMENT_STORE_OP_STORE);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default();
            rendering.renderArea().extent().set(width, height);
            rendering.layerCount(1)
                     .pDepthAttachment(depth);
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, rendering);
        }
        setViewportScissor(cmd, width, height);

        if (hasInstanceOit) {
            VkContext.pushLabel(cmd, "flywheel:vk/oit/mlab/instances");
            VK12.vkCmdBindIndexBuffer(cmd, indexVk, 0L, VK12.VK_INDEX_TYPE_UINT32);
            bindVertexBuffer(cmd, vertexVk);
            m.drawMlabProducerGeometry(mode, cmd, frame, mlab);
            VkContext.popLabel(cmd);
        }
        if (terrain instanceof VkFoldedOitReplay t) {
            t.replayMlab(cmd, mode, mlab, lightmapView, clampLinear);
        }
        if (chunks != null) {
            insertChunks(cmd, frame, chunks, mlab);
        }
        if (ber != null && !ber.isEmpty()) {
            insertBer(cmd, frame, ber, mlab);
        }
        if (OitConfig.exactFabulous() && fabulous != null && fabulous.hasWeather()) {
            insertWeather(cmd, frame, fabulous, mlab);
        }

        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
        VkContext.pushLabel(cmd, "flywheel:vk/oit/mlab/publish");
        memoryBarrier(cmd,
                VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK12.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK12.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        VkContext.popLabel(cmd);
        VkContext.popLabel(cmd);
        VkContext.submitCommands(cmd);
    }

    private void insertChunks(VkCommandBuffer cmd, VkOitRenderer.OitFrame frame, ChunkSectionsToRender chunks,
                              VkMlabBuffers mlab) {
        var drawGroup = chunks.drawGroupsPerLayer().get(ChunkSectionLayer.TRANSLUCENT);
        if (drawGroup == null || drawGroup.isEmpty()) {
            return;
        }
        VkContext.pushLabel(cmd, "flywheel:vk/oit/chunk/" + mode);
        VkGraphicsPipeline pipeline = m.programs.oit().chunkMlabPipeline(mode);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        long atlasView = VkContext.imageView(chunks.textureView());
        long sharedIndexBuffer = chunkSharedIndexBuffer(chunks);
        for (var draws : drawGroup.values()) {
            for (RenderPass.Draw<GpuBufferSlice[]> draw : draws.reversed()) {
                bindChunkDraw(cmd, frame, chunks, atlasView, sharedIndexBuffer, draw);
                mlab.bind(m.writer);
                m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
                VK12.vkCmdDrawIndexed(cmd, draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
            }
        }
        VkContext.popLabel(cmd);
    }

    private void insertBer(VkCommandBuffer cmd, VkOitRenderer.OitFrame frame, BerTranslucentCapture ber,
                           VkMlabBuffers mlab) {
        VkContext.pushLabel(cmd, "flywheel:vk/oit/ber/" + mode);
        for (BerFamily family : BerFamily.VALUES) {
            List<BerTranslucentCapture.CapturedDraw> draws = ber.draws(family);
            if (draws.isEmpty()) {
                continue;
            }
            VkGraphicsPipeline pipeline = m.programs.oit().berMlabPipeline(family, mode);
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            for (BerTranslucentCapture.CapturedDraw draw : draws) {
                bindBerDraw(cmd, frame, family, draw);
                mlab.bind(m.writer);
                m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
                StagedVertexBuffer.ExecuteInfo info = draw.info();
                VK12.vkCmdDrawIndexed(cmd, info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
            }
        }
        VkContext.popLabel(cmd);
    }

    private void insertWeather(VkCommandBuffer cmd, VkOitRenderer.OitFrame frame, FabulousCaptures fab,
                               VkMlabBuffers mlab) {
        VkContext.pushLabel(cmd, "flywheel:vk/oit/fabulous/" + mode);
        VkGraphicsPipeline pipeline = m.programs.oit().weatherMlabPipeline(mode);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        bindVertexBuffer(cmd, VkContext.buffer(fab.weatherVertices));
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexGpu = indices.getBuffer(6 * (fab.rainColumns + fab.snowColumns));
        int indexType = indices.type() == IndexType.SHORT ? VK12.VK_INDEX_TYPE_UINT16 : VK12.VK_INDEX_TYPE_UINT32;
        VK12.vkCmdBindIndexBuffer(cmd, VkContext.buffer(indexGpu), 0L, indexType);

        TextureManager textureManager = frame.textureManager();
        drawWeatherRange(cmd, frame, fab, mlab, pipeline,
                textureManager.getTexture(WeatherOitReplay.RAIN_LOCATION), 0, fab.rainColumns);
        drawWeatherRange(cmd, frame, fab, mlab, pipeline,
                textureManager.getTexture(WeatherOitReplay.SNOW_LOCATION), fab.rainColumns, fab.snowColumns);
        VkContext.popLabel(cmd);
    }

    private void drawWeatherRange(VkCommandBuffer cmd, VkOitRenderer.OitFrame frame, FabulousCaptures fab,
                                  VkMlabBuffers mlab,
                                  VkGraphicsPipeline pipeline, AbstractTexture texture, int startColumn,
                                  int columnCount) {
        if (columnCount == 0) {
            return;
        }
        m.writer.sampler(10, VkContext.imageView(texture.getTextureView()), VkContext.sampler(texture.getSampler()))
                .sampler(12, frame.lightmapView(), frame.overlaySampler())
                .uniform(16, frame.projection())
                .uniform(17, fab.weatherTransform)
                .uniform(18, frame.fog());
        mlab.bind(m.writer);
        m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
        VK12.vkCmdDrawIndexed(cmd, columnCount * 6, 1, startColumn * 6, 0, 0);
    }

    private void resolve(CommandEncoder encoder, RenderPassDescriptor descriptor, int width, int height,
                         VkOitRenderer.OitFrame frame, @Nullable FabulousCaptures fab) {
        FlwPassBarrier.expectFramebufferProducer();
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            VkCommandBuffer cmd = ((VulkanRenderPass) pass.backend).commandBuffer;
            setViewportScissor(cmd, width, height);
            VkContext.pushLabel(cmd, "flywheel:vk/oit/composite");
            VkGraphicsPipeline pipeline = m.programs.oit().mlabResolvePipeline(mode);
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            // Layer-merge inputs; absent layers get a mask-guarded placeholder -- never the pass's own depth attachment (descriptor-level feedback loop).
            long placeholder = frame.lightmapView();
            boolean clouds = fab != null && fab.hasClouds();
            boolean item = fab != null && fab.hasItemLayer();
            boolean particle = fab != null && fab.hasParticleLayer();
            m.writer.sampler(29, clouds ? VkContext.imageView(framebuffer.cloudsColorView()) : placeholder,
                    frame.oitSampler());
            m.writer.sampler(30, clouds ? VkContext.imageView(framebuffer.cloudsDepthView()) : placeholder,
                    frame.oitSampler());
            m.writer.sampler(32, item ? VkContext.imageView(fab.itemLayerColor) : placeholder, frame.oitSampler());
            m.writer.sampler(33, item ? VkContext.imageView(fab.itemLayerDepth) : placeholder, frame.oitSampler());
            m.writer.sampler(34, particle ? VkContext.imageView(fab.particleLayerColor) : placeholder,
                    frame.oitSampler());
            m.writer.sampler(35, particle ? VkContext.imageView(fab.particleLayerDepth) : placeholder,
                    frame.oitSampler());
            boolean weather = fab != null && fab.hasWeather() && !OitConfig.exactFabulous();
            m.writer.sampler(36, weather ? VkContext.imageView(framebuffer.weatherColorView()) : placeholder,
                    frame.oitSampler());
            m.writer.sampler(37, weather ? VkContext.imageView(framebuffer.weatherDepthView()) : placeholder,
                    frame.oitSampler());
            buffers().bind(m.writer);
            m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            VK12.vkCmdDraw(cmd, 3, 1, 0, 0);
            VkContext.popLabel(cmd);
        } finally {
            FlwPassBarrier.clear();
        }
    }

    void delete() {
        releasePayload();
        if (countOrHead != null) {
            countOrHead.delete();
            countOrHead = null;
        }
        if (ubo != null) {
            ubo.delete();
            ubo = null;
        }
    }
}
