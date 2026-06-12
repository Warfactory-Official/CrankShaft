package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Add {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT} to every Vulkan buffer while a BDA-consuming terrain
 * path is active (mesh-shader deref of the Sodium arena by device address).
 */
@Mixin(VulkanGpuBuffer.Direct.class)
abstract class VulkanGpuBufferMixin {
    @ModifyExpressionValue(method = "<init>(Lcom/mojang/blaze3d/vulkan/VulkanDevice;Ljava/util/function/Supplier;IJZ)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanConst;bufferUsageToVk(I)I"))
    private static int flywheel$addShaderDeviceAddress(int vkUsage) {
        boolean bda = VkCaps.MESH_SHADER_NEGOTIATED || VkCaps.BUFFER_DEVICE_ADDRESS_NEGOTIATED;
        return bda ? vkUsage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT : vkUsage;
    }
}
