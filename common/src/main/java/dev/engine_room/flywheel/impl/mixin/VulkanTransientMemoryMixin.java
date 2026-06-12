package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.vulkan.VulkanTransientMemory;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Add {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT} to vanilla's transient-memory block buffers.
 */
@Mixin(VulkanTransientMemory.class)
abstract class VulkanTransientMemoryMixin {
    @ModifyArg(method = "allocateVulkanBlock", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkBufferCreateInfo;usage(I)Lorg/lwjgl/vulkan/VkBufferCreateInfo;"))
    private int flywheel$addShaderDeviceAddress(int usage) {
        return VkCaps.DESCRIPTOR_BUFFER_NEGOTIATED ? usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT : usage;
    }
}
