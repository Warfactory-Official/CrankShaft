package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;

final class VkAbufferOitChain extends VkInsertOitChain {
    private static final int ABUF_DEPTH = 8;

    @Nullable
    private VkBuffer nodes;
    @Nullable
    private VkBuffer counter;

    VkAbufferOitChain(VkIndirectDrawManager m, OitFramebuffer framebuffer) {
        super(m, framebuffer, OitInsertMode.ABUFFER);
    }

    @Override
    int maxNodes(long pixels) {
        return (int) Math.min(pixels * ABUF_DEPTH, Integer.MAX_VALUE);
    }

    @Override
    void ensurePayload(long pixels, int layers, int maxNodes) {
        long nodesBytes = (long) maxNodes * 16L; // uvec4 per node
        if (nodes == null) {
            nodes = new VkBuffer(VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, nodesBytes, true);
            counter = new VkBuffer(VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    Integer.BYTES, true);
        } else {
            nodes.ensureCapacity(nodesBytes);
        }
    }

    @Override
    void releasePayload() {
        if (nodes != null) {
            nodes.delete();
            nodes = null;
        }
        if (counter != null) {
            counter.delete();
            counter = null;
        }
    }

    @Override
    VkMlabBuffers buffers() {
        return new VkMlabBuffers(countOrHead, nodes, ubo, counter);
    }

    @Override
    int headClearValue() {
        return 0xFFFFFFFF; // NULL list heads
    }

    @Override
    void recordClearExtra(VkCommandBuffer cmd) {
        VK12.vkCmdFillBuffer(cmd, counter.vkBuffer(), 0L, Integer.BYTES, 0);
    }
}
