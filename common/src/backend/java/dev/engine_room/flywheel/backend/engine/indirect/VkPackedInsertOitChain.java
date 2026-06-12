package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK12;

abstract class VkPackedInsertOitChain extends VkInsertOitChain {
    @Nullable
    private VkBuffer samples;

    VkPackedInsertOitChain(VkIndirectDrawManager m, OitFramebuffer framebuffer, OitInsertMode mode) {
        super(m, framebuffer, mode);
    }

    @Override
    void ensurePayload(long pixels, int layers, int maxNodes) {
        long dataBytes = pixels * layers * 8L; // K samples/pixel
        if (samples == null) {
            samples = new VkBuffer(VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, dataBytes, true);
        } else {
            samples.ensureCapacity(dataBytes);
        }
    }

    @Override
    void releasePayload() {
        if (samples != null) {
            samples.delete();
            samples = null;
        }
    }

    @Override
    VkMlabBuffers buffers() {
        return new VkMlabBuffers(countOrHead, samples, ubo, null);
    }

    @Override
    int headClearValue() {
        return 0;
    }
}
