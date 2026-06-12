package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Add {@code VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT} to sampled color render-attachments while dynamic-rendering local-read is negotiated.
 */
@Mixin(VulkanGpuTexture.class)
abstract class VulkanGpuTextureMixin {
    @ModifyExpressionValue(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;ILjava/lang/String;Lcom/mojang/blaze3d/GpuFormat;IIII)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanConst;textureUsageToVk(ILcom/mojang/blaze3d/GpuFormat;)I"))
    private static int flywheel$addInputAttachmentUsage(int vkUsage) {
        boolean colorAttachmentAndSampled = (vkUsage & VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0
                && (vkUsage & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) != 0;
        return VkCaps.DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED && colorAttachmentAndSampled
                ? vkUsage | VK12.VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT : vkUsage;
    }
}
