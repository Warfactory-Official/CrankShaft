package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import dev.engine_room.flywheel.backend.vk.VkDeviceFault;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanUtils.class)
abstract class VulkanUtilsMixin {
    @Inject(method = "crashIfFailure", at = @At("HEAD"))
    private static void flywheel$dumpDeviceFault(VulkanDevice device, int result, String message, CallbackInfo ci) {
        if (result == VK12.VK_ERROR_DEVICE_LOST) {
            VkDeviceFault.dump();
        }
    }
}
