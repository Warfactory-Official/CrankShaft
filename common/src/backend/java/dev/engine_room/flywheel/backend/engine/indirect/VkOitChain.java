package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.engine_room.flywheel.backend.engine.BerFamily;
import dev.engine_room.flywheel.backend.engine.BerTranslucentCapture;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.engine.ChunkReplayData;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;
#endif
import dev.engine_room.flywheel.backend.vk.VkContext;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static dev.engine_room.flywheel.backend.vk.VkCmd.bindVertexBuffer;

abstract class VkOitChain {
    final VkIndirectDrawManager m;
    final OitFramebuffer framebuffer;
    #if MC >= 26.3
    private final RenderPass.UniformUploader chunkSectionCapture = new RenderPass.UniformUploader() {
        @Override public void setUniform(String name, GpuBufferSlice slice) {
            capturedChunkSection = slice;
        }
        @Override public void pushConstants(ByteBuffer data) {
            throw new UnsupportedOperationException("Vanilla chunk draw uploaded unexpected push constants");
        }
    };
    #else
    private final RenderPass.UniformUploader chunkSectionCapture = (name, slice) -> capturedChunkSection = slice;
    #endif
    private GpuBufferSlice capturedChunkSection;

    VkOitChain(VkIndirectDrawManager m, OitFramebuffer framebuffer) {
        this.m = m;
        this.framebuffer = framebuffer;
    }

    /** Engine instances: mesh-pool index and vertex buffers. */
    static void bindInstances(VkCommandBuffer cmd, long vertexVk, long indexVk) {
        VK12.vkCmdBindIndexBuffer(cmd, indexVk, 0L, VK12.VK_INDEX_TYPE_UINT32);
        bindVertexBuffer(cmd, vertexVk);
    }

    static long chunkSharedIndexBuffer(ChunkSectionsToRender chunks) {
        #if MC >= 26.3
        int maxIndices = ChunkReplayData.maxIndices(chunks);
        #else
        int maxIndices = chunks.maxIndicesRequired();
        #endif
        if (maxIndices == 0) {
            return 0L;
        }
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        return VkContext.buffer(autoIndices.getBuffer(maxIndices));
    }

    void bindChunkDraw(VkCommandBuffer cmd, VkOitRenderer.OitFrame frame, ChunkSectionsToRender chunks, long atlasView,
                       long sharedIndexBuffer, RenderPass.Draw<GpuBufferSlice[]> draw) {
        bindVertexBuffer(cmd, VkContext.buffer(draw.vertexBuffer()));
        if (draw.indexBuffer() != null) {
            int indexType = draw.indexType() == IndexType.SHORT ? VK12.VK_INDEX_TYPE_UINT16 : VK12.VK_INDEX_TYPE_UINT32;
            VK12.vkCmdBindIndexBuffer(cmd, VkContext.buffer(draw.indexBuffer()), 0L, indexType);
        } else {
            RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            VK12.vkCmdBindIndexBuffer(cmd, sharedIndexBuffer, 0L,
                    autoIndices.type() == IndexType.SHORT ? VK12.VK_INDEX_TYPE_UINT16 : VK12.VK_INDEX_TYPE_UINT32);
        }

        #if MC >= 26.3
        draw.uniformUploaderConsumer().accept(ChunkReplayData.separateInfos(chunks), chunkSectionCapture);
        m.writer.uniform(42, ChunkReplayData.terrainUniform(chunks));
        #else
        draw.uniformUploaderConsumer().accept(chunks.chunkSectionInfos(), chunkSectionCapture);
        #endif
        m.writer.sampler(10, atlasView, frame.atlasSampler())
                .sampler(12, frame.lightmapView(), frame.overlaySampler())
                .uniform(16, frame.projection())
                .uniform(18, frame.fog())
                .uniform(20, frame.globals())
                .uniform(21, capturedChunkSection);
    }

    #if MC >= 26.3
    void writeIndirectChunkUniforms(VkOitRenderer.OitFrame frame, ChunkSectionsToRender.DrawIndirect chunks,
                                   long atlasView) {
        m.writer.sampler(10, atlasView, frame.atlasSampler())
                .sampler(12, frame.lightmapView(), frame.overlaySampler())
                .uniform(16, frame.projection())
                .uniform(18, frame.fog())
                .uniform(20, frame.globals())
                .uniform(42, chunks.terrainTransformUBO);
    }

    void drawIndirectChunks(VkCommandBuffer cmd, ChunkSectionsToRender.DrawIndirect chunks, long sharedIndexBuffer) {
        int maxDrawCount = (int) Math.min(VkCaps.MAX_DRAW_INDIRECT_COUNT, Integer.MAX_VALUE);
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var vertexBuffer = stack.mallocLong(1);
            var vertexOffset = stack.mallocLong(1);
            vertexBuffer.put(0, VkContext.buffer(chunks.chunkSectionInfos.buffer()));
            vertexOffset.put(0, chunks.chunkSectionInfos.offset());
            VK12.vkCmdBindVertexBuffers(cmd, 1, vertexBuffer, vertexOffset);
            for (var draw : chunks.drawGroupsPerLayer.get(ChunkSectionLayer.TRANSLUCENT)) {
                if (draw.drawCount() == 0) continue;
                vertexBuffer.put(0, VkContext.buffer(draw.vertexBuffer().buffer()));
                vertexOffset.put(0, draw.vertexBuffer().offset());
                VK12.vkCmdBindVertexBuffers(cmd, 0, vertexBuffer, vertexOffset);
                IndexType indexType = draw.indexType() == null ? autoIndices.type() : draw.indexType();
                VK12.vkCmdBindIndexBuffer(cmd,
                        draw.indexBuffer() == null ? sharedIndexBuffer : VkContext.buffer(draw.indexBuffer().buffer()),
                        draw.indexBuffer() == null ? 0L : draw.indexBuffer().offset(),
                        indexType == IndexType.SHORT ? VK12.VK_INDEX_TYPE_UINT16 : VK12.VK_INDEX_TYPE_UINT32);
                long commandBuffer = VkContext.buffer(draw.indirectCommandBuffer().buffer());
                long commandOffset = draw.indirectCommandBuffer().offset();
                int remaining = draw.drawCount();
                // 26.3: preserve native commands/baseInstance and split only at the device's draw-count limit.
                while (remaining > 0) {
                    int count = Math.min(remaining, maxDrawCount);
                    VK12.vkCmdDrawIndexedIndirect(cmd, commandBuffer, commandOffset, count,
                            VkDrawIndexedIndirectCommand.SIZEOF);
                    remaining -= count;
                    commandOffset += (long) count * VkDrawIndexedIndirectCommand.SIZEOF;
                }
            }
        }
    }
    #endif

    void bindBerDraw(VkCommandBuffer cmd, VkOitRenderer.OitFrame frame, BerFamily family,
                     BerTranslucentCapture.CapturedDraw draw) {
        StagedVertexBuffer.ExecuteInfo info = draw.info();
        bindVertexBuffer(cmd, VkContext.buffer(info.vertexBuffer()));
        int indexType = info.indexType() == IndexType.SHORT ? VK12.VK_INDEX_TYPE_UINT16 : VK12.VK_INDEX_TYPE_UINT32;
        VK12.vkCmdBindIndexBuffer(cmd, VkContext.buffer(info.indexBuffer()), 0L, indexType);

        PreparedRenderType renderType = draw.renderType();
        for (PreparedRenderType.Texture texture : renderType.textures()) {
            if ("Sampler0".equals(texture.name())) {
                m.writer.sampler(10, VkContext.imageView(texture.textureView()), VkContext.sampler(texture.sampler()));
            }
        }
        if (family.overlay) {
            m.writer.sampler(11, frame.overlayView(), frame.overlaySampler());
        }
        if (family.lightmap) {
            m.writer.sampler(12, frame.lightmapView(), frame.overlaySampler());
        }
        m.writer.uniform(16, frame.projection())
                .uniform(17, renderType.dynamicTransforms())
                .uniform(18, frame.fog());
        if (family.lighting) {
            m.writer.uniform(19, frame.lights());
        }
    }
}
