package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
#endif
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
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
#if MC >= 26.3
import java.util.HashSet;
#endif
import java.util.Set;

@Mixin(VulkanBackend.class)
abstract class VulkanBackendMixin {
    #if MC >= 26.3
    // 26.3: FeatureSet sets are immutable; replace the aggregate before native creation and capability recording.
    @ModifyExpressionValue(
            method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(value = "NEW", target = "Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;"))
    private FeatureSet flywheel$appendDeviceRequests(FeatureSet featureSet,
                                                      @Local VulkanPhysicalDevice physicalDevice) {
        Set<String> extensions = new HashSet<>(featureSet.extensions());
        Set<VulkanFeature> features = new HashSet<>(featureSet.features());
        VkDeviceNegotiation.appendDeviceRequests(extensions, physicalDevice, features);
        return new FeatureSet(featureSet.name(), extensions, features, featureSet.condition());
    }
    #else
    @Inject(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;", at = @At("HEAD"))
    private static void flywheel$appendDeviceRequests(Collection<String> deviceExtensions,
                                                      VulkanPhysicalDevice physicalDevice,
                                                      Set<VulkanFeature> vulkanFeatures,
                                                      CallbackInfoReturnable<VkDevice> cir) {
        VkDeviceNegotiation.appendDeviceRequests(deviceExtensions, physicalDevice, vulkanFeatures);
    }
    #endif

    #if MC >= 26.3
    @Redirect(
            method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    #else
    @Redirect(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    #endif
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
