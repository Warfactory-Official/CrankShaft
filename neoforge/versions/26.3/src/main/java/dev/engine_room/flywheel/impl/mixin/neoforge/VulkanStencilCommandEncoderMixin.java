package dev.engine_room.flywheel.impl.mixin.neoforge;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.engine_room.flywheel.impl.extension.VulkanStencilPassAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanCommandEncoder.class)
abstract class VulkanStencilCommandEncoderMixin {
    @Inject(method = "createRenderPass", at = @At("RETURN"))
    private void flywheel$recordDepthFormat(RenderPassDescriptor descriptor,
                                           CallbackInfoReturnable<RenderPassBackend> cir) {
        if (descriptor.depthAttachment() != null) {
            ((VulkanStencilPassAccess) cir.getReturnValue()).flywheel$depthFormat(
                    VulkanConst.toVk(descriptor.depthAttachment().textureView().texture().getFormat()));
        }
    }
}
