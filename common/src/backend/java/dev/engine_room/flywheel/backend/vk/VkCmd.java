package dev.engine_room.flywheel.backend.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

public final class VkCmd {
    private VkCmd() {
    }

    public static void memoryBarrier(VkCommandBuffer cmd, int srcStage, int dstStage, int srcAccess, int dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack)
                                                      .sType$Default()
                                                      .srcAccessMask(srcAccess)
                                                      .dstAccessMask(dstAccess);
            VK12.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, b, null, null);
        }
    }

    public static void setViewportScissor(VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer vp = VkViewport.calloc(1, stack);
            vp.x(0.0F).y(0.0F).width(width).height(height).minDepth(0.0F).maxDepth(1.0F);
            VK12.vkCmdSetViewport(cmd, 0, vp);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(width, height);
            VK12.vkCmdSetScissor(cmd, 0, scissor);
        }
    }

    public static void bindVertexBuffer(VkCommandBuffer cmd, long vertexVk) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexVk), stack.longs(0L));
        }
    }
}
