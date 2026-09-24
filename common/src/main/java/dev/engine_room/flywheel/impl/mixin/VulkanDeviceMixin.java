package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.engine_room.flywheel.backend.vk.VkContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
abstract class VulkanDeviceMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void flywheel$destroyEngineObjects(CallbackInfo ci) {
        VkContext.shutdown();
    }
}
