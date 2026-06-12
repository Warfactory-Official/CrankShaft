package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorHeap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every {@code pushDescriptors} call invalidates the engine's descriptor-buffer binding on the shared command buffer.
 */
@Mixin(VulkanRenderPass.class)
abstract class VulkanRenderPassMixin {
    @Shadow
    private boolean anyDescriptorDirty;

    @Inject(method = "pushDescriptors", at = @At("HEAD"))
    private void flywheel$notePushDescriptors(CallbackInfo ci) {
        if (anyDescriptorDirty) {
            VkDescriptorHeap.notifyForeignBind();
        }
    }
}
