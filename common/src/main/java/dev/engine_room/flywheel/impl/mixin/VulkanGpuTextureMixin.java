package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
#endif
import dev.engine_room.flywheel.backend.vk.VkCaps;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Marks sampled render attachments for local reads. The native subpass graph also reads stored
 * main depth while a distinct native water/cloud depth attachment is active.
 */
@Mixin(VulkanGpuTexture.class)
abstract class VulkanGpuTextureMixin {
    @ModifyExpressionValue(
            method =
            #if MC >= 26.3
            "<init>(Lcom/mojang/renderpearl/backend/vulkan/VulkanDevice;ILjava/lang/String;Lcom/mojang/renderpearl/api/GpuFormat;IIII)V",
            #else
            "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;ILjava/lang/String;Lcom/mojang/blaze3d/GpuFormat;IIII)V",
            #endif
            at = @At(value = "INVOKE",
                    target =
                    #if MC >= 26.3
                    "Lcom/mojang/renderpearl/backend/vulkan/VulkanConst;textureUsageToVk(ILcom/mojang/renderpearl/api/GpuFormat;)I"))
                    #else
                    "Lcom/mojang/blaze3d/vulkan/VulkanConst;textureUsageToVk(ILcom/mojang/blaze3d/GpuFormat;)I"))
                    #endif
    private static int flywheel$addInputAttachmentUsage(int vkUsage) {
        boolean colorAttachmentAndSampled = (vkUsage & VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0
                && (vkUsage & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) != 0;
        boolean localRead = VkCaps.DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED && colorAttachmentAndSampled;
        #if MC >= 26.3
        localRead |= VkNativeOitPass.ENABLED && (vkUsage & VK12.VK_IMAGE_USAGE_SAMPLED_BIT) != 0
                && (vkUsage & (VK12.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                        | VK12.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)) != 0;
        #endif
        return localRead
                ? vkUsage | VK12.VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT : vkUsage;
    }
}
