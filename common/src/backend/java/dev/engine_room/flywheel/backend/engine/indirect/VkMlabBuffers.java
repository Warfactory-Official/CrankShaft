package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorWriter;
import org.jspecify.annotations.Nullable;

public record VkMlabBuffers(VkBuffer buf24, VkBuffer buf25, VkBuffer ubo, @Nullable VkBuffer counter) {
    public void bind(VkDescriptorWriter writer) {
        writer.storage(24, buf24)
              .storage(25, buf25)
              .uniform(26, ubo.vkBuffer(), 0L, ubo.sizeBytes());
        if (counter != null) {
            writer.storage(27, counter);
        }
    }
}
