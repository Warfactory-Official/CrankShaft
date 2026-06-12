package dev.engine_room.flywheel.backend.vk;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

public final class VkLayerTextureView extends VulkanGpuTextureView {
    private final long layerView;

    private VkLayerTextureView(VulkanGpuTexture texture, int layer) {
        super(VkContext.device(), texture, 0, 1);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                                                              .sType$Default()
                                                              .image(texture.vkImage())
                                                              .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                                                              .format(VulkanConst.toVk(texture.getFormat()));
            info.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(layer)
                .layerCount(1);
            LongBuffer handle = stack.callocLong(1);
            int result = VK12.vkCreateImageView(VkContext.vkDevice(), info, null, handle);
            if (result != VK12.VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateImageView failed for coefficient layer " + layer + ": " + result);
            }
            layerView = handle.get(0);
        }
    }

    public static GpuTextureView create(GpuTexture arrayTexture, int layer) {
        return new VkLayerTextureView((VulkanGpuTexture) arrayTexture, layer);
    }

    @Override
    public long vkImageView() {
        return layerView;
    }

    @Override
    public void destroy() {
        VK12.vkDestroyImageView(VkContext.vkDevice(), layerView, null);
        super.destroy();
    }
}
