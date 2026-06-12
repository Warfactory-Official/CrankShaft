package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.engine.BerFamily;
import dev.engine_room.flywheel.backend.engine.BerTranslucentCapture;
import dev.engine_room.flywheel.backend.vk.VkContext;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

import static dev.engine_room.flywheel.backend.vk.VkCmd.bindVertexBuffer;

abstract class VkOitChain {
    final VkIndirectDrawManager m;
    final OitFramebuffer framebuffer;
    private final RenderPass.UniformUploader chunkSectionCapture = (name, slice) -> capturedChunkSection = slice;
    private GpuBufferSlice capturedChunkSection;

    VkOitChain(VkIndirectDrawManager m, OitFramebuffer framebuffer) {
        this.m = m;
        this.framebuffer = framebuffer;
    }

    static long chunkSharedIndexBuffer(ChunkSectionsToRender chunks) {
        if (chunks.maxIndicesRequired() == 0) {
            return 0L;
        }
        RenderSystem.AutoStorageIndexBuffer autoIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        return VkContext.buffer(autoIndices.getBuffer(chunks.maxIndicesRequired()));
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

        draw.uniformUploaderConsumer().accept(chunks.chunkSectionInfos(), chunkSectionCapture);
        m.writer.sampler(10, atlasView, frame.atlasSampler())
                .sampler(12, frame.lightmapView(), frame.overlaySampler())
                .uniform(16, frame.projection())
                .uniform(18, frame.fog())
                .uniform(20, frame.globals())
                .uniform(21, capturedChunkSection);
    }

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
