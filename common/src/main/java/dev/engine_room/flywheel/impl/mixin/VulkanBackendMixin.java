package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import dev.engine_room.flywheel.backend.vk.VkDeviceNegotiation;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Set;

@Mixin(VulkanBackend.class)
abstract class VulkanBackendMixin {
    @Inject(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;", at = @At("HEAD"))
    private static void flywheel$appendDeviceRequests(Collection<String> deviceExtensions,
                                                      VulkanPhysicalDevice physicalDevice,
                                                      Set<VulkanFeature> vulkanFeatures,
                                                      CallbackInfoReturnable<VkDevice> cir) {
        VkDeviceNegotiation.appendDeviceRequests(deviceExtensions, physicalDevice, vulkanFeatures);
    }

    @Redirect(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int flywheel$negotiateDeviceFeatures(VkPhysicalDevice vkPhysicalDevice,
                                                        VkDeviceCreateInfo createInfo,
                                                        VkAllocationCallbacks allocator, PointerBuffer pDevice) {
        return VkDeviceNegotiation.createDevice(vkPhysicalDevice, createInfo, allocator, pDevice);
    }

    @Redirect(method = "createVma(Lorg/lwjgl/vulkan/VkDevice;)J", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaCreateAllocator(Lorg/lwjgl/util/vma/VmaAllocatorCreateInfo;Lorg/lwjgl/PointerBuffer;)I"))
    private static int flywheel$enableVmaBufferDeviceAddress(VmaAllocatorCreateInfo createInfo,
                                                             PointerBuffer pAllocator) {
        return VkDeviceNegotiation.createVma(createInfo, pAllocator);
    }
}
