package dev.engine_room.flywheel.backend.vk.shader;

import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import org.jspecify.annotations.Nullable;

/**
 * A pipeline drawn through {@link VkGraphicsPipeline.MaterialState}: variants share one owner's modules and layout.
 * Render thread.
 */
public interface VkMaterialPipeline {
    /**
     * {@code subpass}: null under dynamic rendering; {@code depth}: null where the device sets depth state dynamically.
     */
    long variant(VkGraphicsPipeline.@Nullable Subpass subpass, VkGraphicsPipeline.@Nullable DepthState depth);

    VkDescriptorLayout layout();
}
