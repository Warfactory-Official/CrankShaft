package dev.engine_room.flywheel.backend.engine.indirect;

#if MC >= 26.3
import dev.engine_room.flywheel.backend.engine.ChunkReplayData;
#endif

import dev.engine_room.flywheel.compat.backend.RenderPassOps;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.ProgramAvailability;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.vk.FlwPassBarrier;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

#if MC < 26.3
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
#endif

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import static dev.engine_room.flywheel.backend.vk.VkCmd.*;

abstract class VkInsertOitChain extends VkOitChain {
    #if MC >= 26.3
    // Resolve layer composite inputs; no Fabulous layer feeds them.
    private static final int[] LAYER_BINDINGS = {29, 30, 32, 33, 34, 35, 36, 37};
    #endif
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

    void render(VkOitRenderer.ChainInputs in, boolean fused) {
        #if MC < 26.3
        FabulousCaptures fabulous = in.replay().fabulous();
        int layerMask = fabulous != null ? fabulous.layerMask(!OitConfig.exactFabulous()) : 0;
        #else
        int layerMask = 0;
        #endif
        VkMlabBuffers mlab;
        try {
            mlab = ensureStorage(in.width(), in.height(), layerMask);
        } catch (ProgramAvailability.Failure failure) {
            throw failure;
        } catch (BackendUnavailableException failure) {
            throw new ProgramAvailability.Failure(ProgramAvailability.insert(mode), failure);
        }
        producers(in, mlab, fused);
        framebuffer.prepareNearestDepth();
        RenderPassDescriptor resolveDescriptor = RenderPassOps.builder(() -> "flywheel:vk/oit/resolve")
                .withColorAttachment(in.colorView())
                .withColorAttachment(framebuffer.nearestDepthView(), Optional.<Vector4fc>of(new Vector4f(-1.0f)))
                .withDepthAttachment(in.depthView(), OptionalDouble.empty())
                .withRenderArea(new RenderPass.RenderArea(0, 0, in.width(), in.height()))
                .build();
        resolve(in, resolveDescriptor);
        nearestDepth(in.encoder(), in.compositeDescriptor(), in.frame(), in.width(), in.height());
    }

    private VkMlabBuffers ensureStorage(int width, int height, int layerMask) {
        long pixels = (long) width * height;
        int nodes = maxNodes(pixels);
        int layers = OitConfig.layersFor(mode);
        int storage = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (ubo == null) {
            ubo = new VkBuffer(VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, 32L);
        }
        if (countOrHead == null) {
            countOrHead = new VkBuffer(storage, pixels * Integer.BYTES, true);
        } else {
            countOrHead.ensureCapacity(pixels * Integer.BYTES);
        }
        ensurePayload(pixels, layers, nodes);

        long uboPtr = ubo.mappedAddress();
        MemoryUtil.memPutInt(uboPtr, width);
        MemoryUtil.memPutInt(uboPtr + 4L, height);
        MemoryUtil.memPutInt(uboPtr + 8L, nodes);
        MemoryUtil.memPutInt(uboPtr + 12L, layers);
        MemoryUtil.memPutInt(uboPtr + 16L, layerMask);
        return buffers();
    }

    private void producers(VkOitRenderer.ChainInputs in, VkMlabBuffers mlab, boolean fused) {
        int width = in.width();
        int height = in.height();
        long pixels = (long) width * height;

        VkCommandBuffer cmd = VkContext.beginCommands();
        VkContext.pushLabel(cmd, "flywheel:vk/oit/" + mode);
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

        NativeOitReplay nativeReplay = in.nativeReplay();
        if (fused && nativeReplay != null) {
            nativeProducers(in, nativeReplay, mlab);
        } else {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkRenderingInfo rendering = VkRenderingInfo.calloc(stack).sType$Default();
                rendering.renderArea().extent().set(width, height);
                rendering.layerCount(1);
                VkContext.attachDepth(stack, rendering, in.depthView());
                KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, rendering);
            }
            setViewportScissor(cmd, width, height);

            VkOitRenderer.OitFrame frame = in.frame();
            VkOitRenderer.OitReplay replay = in.replay();
            if (in.hasInstanceOit()) {
                VkContext.pushLabel(cmd, "flywheel:vk/oit/mlab/instances");
                bindInstances(cmd, in.vertexVk(), in.indexVk());
                m.drawMlabProducerGeometry(mode, cmd, frame, mlab);
                VkContext.popLabel(cmd);
            }
            if (replay.terrain() instanceof VkFoldedOitReplay t) {
                t.replayMlab(cmd, mode, mlab, replay.lightmapView(), replay.loSampler());
            }
            if (replay.chunks() != null) {
                insertChunks(cmd, frame, replay.chunks(), mlab);
            }
            if (replay.ber() != null && !replay.ber().isEmpty()) {
                insertBer(cmd, frame, replay.ber(), mlab);
            }
            #if MC < 26.3
            if (OitConfig.exactFabulous() && replay.fabulous() != null && replay.fabulous().hasWeather()) {
                insertWeather(cmd, frame, replay.fabulous(), mlab);
            }
            #endif

            KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
            if (nativeReplay != null) {
                // Raw pass: no renderpearl post-pass barrier; native passes append to its storage.
                memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                        VK12.VK_ACCESS_SHADER_WRITE_BIT,
                        VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
                nativeReplay.replayInsert(RenderPassOps.builder(() -> "flywheel:vk/oit/native_insert")
                                .withDepthAttachment(in.depthView(), OptionalDouble.empty())
                                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height))
                                .build(),
                        mode, mlab, in.nativeFrame());
            }
        }
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

    private void nativeProducers(VkOitRenderer.ChainInputs in, NativeOitReplay replay, VkMlabBuffers storage) {
        #if MC >= 26.3
        var targets = replay.vulkanTargets(framebuffer, true);
        VkNativeOitPass.get(VkNativeOitPass.spec(targets, true)).record(targets, replay.vulkanBackendFactory(), true,
                in.width(), in.height(), in.far(), owner -> {
                    if (in.hasInstanceOit() && owner.phase() == VkNativeOitPass.Phase.INSERT_MAIN) {
                        bindInstances(owner.commandBuffer(), in.vertexVk(), in.indexVk());
                        m.drawMlabProducerGeometry(mode, owner.commandBuffer(), in.frame(), storage);
                    }
                    replay.replayVulkanCategory(owner, mode, storage, framebuffer, in.nativeFrame());
                });
        #else
        throw new UnsupportedOperationException("Native OIT categories require Minecraft 26.3");
        #endif
    }

    private void insertChunks(VkCommandBuffer cmd, VkOitRenderer.OitFrame frame, ChunkSectionsToRender chunks,
                              VkMlabBuffers mlab) {
        #if MC >= 26.3
        if (chunks instanceof ChunkSectionsToRender.DrawIndirect indirect) {
            if (indirect.drawGroupsPerLayer.get(ChunkSectionLayer.TRANSLUCENT).isEmpty()) return;
            VkContext.pushLabel(cmd, "flywheel:vk/oit/chunk_indirect/" + mode);
            VkGraphicsPipeline pipeline = m.programs.oit().chunkMlabPipeline(mode, true);
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            writeIndirectChunkUniforms(frame, indirect, VkContext.imageView(ChunkReplayData.textureView()));
            mlab.bind(m.writer);
            m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            drawIndirectChunks(cmd, indirect, chunkSharedIndexBuffer(chunks));
            VkContext.popLabel(cmd);
            return;
        }
        var draws = ChunkReplayData.separateDraws(chunks);
        if (draws.isEmpty()) return;
        VkContext.pushLabel(cmd, "flywheel:vk/oit/chunk/" + mode);
        VkGraphicsPipeline pipeline = m.programs.oit().chunkMlabPipeline(mode);
        VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        long atlasView = VkContext.imageView(ChunkReplayData.textureView());
        long sharedIndexBuffer = chunkSharedIndexBuffer(chunks);
        for (var draw : draws) {
            bindChunkDraw(cmd, frame, chunks, atlasView, sharedIndexBuffer, draw);
            mlab.bind(m.writer);
            m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            VK12.vkCmdDrawIndexed(cmd, draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
        }
        VkContext.popLabel(cmd);
        #else
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
        #endif
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

    #if MC < 26.3
    private void insertWeather(VkCommandBuffer cmd, VkOitRenderer.OitFrame frame, FabulousCaptures fab,
                               VkMlabBuffers mlab) {
        VkContext.pushLabel(cmd, "flywheel:vk/oit/fabulous/" + mode);
        VkGraphicsPipeline pipeline = m.programs.oit().fabulous().weatherMlabPipeline(mode);
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
    #endif

    private void resolve(VkOitRenderer.ChainInputs in, RenderPassDescriptor descriptor) {
        VkOitRenderer.OitFrame frame = in.frame();
        FlwPassBarrier.expectFramebufferSample();
        try (RenderPass pass = in.encoder().createRenderPass(descriptor)) {
            VkCommandBuffer cmd = VkContext.renderPass(pass).commandBuffer;
            setViewportScissor(cmd, in.width(), in.height());
            VkContext.pushLabel(cmd, "flywheel:vk/oit/composite");
            VkGraphicsPipeline pipeline = m.programs.oit().mlabResolvePipeline(mode);
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            long placeholder = frame.lightmapView();
            #if MC < 26.3
            FabulousCaptures fab = in.replay().fabulous();
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
            #else
            for (int binding : LAYER_BINDINGS) m.writer.sampler(binding, placeholder, frame.oitSampler());
            #endif
            buffers().bind(m.writer);
            m.writer.flush(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout());
            VK12.vkCmdDraw(cmd, 3, 1, 0, 0);
            VkContext.popLabel(cmd);
        } finally {
            FlwPassBarrier.clear();
        }
    }

    private void nearestDepth(CommandEncoder encoder, RenderPassDescriptor descriptor, VkOitRenderer.OitFrame frame,
                              int width, int height) {
        FlwPassBarrier.expectFramebufferProducer();
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            VkCommandBuffer cmd = VkContext.renderPass(pass).commandBuffer;
            setViewportScissor(cmd, width, height);
            VkContext.pushLabel(cmd, "flywheel:vk/oit/nearest_depth");
            VkGraphicsPipeline pipeline = m.programs.oit().mlabNearestDepthPipeline(mode);
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            m.writer.sampler(39, VkContext.imageView(framebuffer.nearestDepthView()), frame.oitSampler());
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
