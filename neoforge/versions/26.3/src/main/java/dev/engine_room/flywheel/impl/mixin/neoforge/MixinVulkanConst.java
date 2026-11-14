package dev.engine_room.flywheel.impl.mixin.neoforge;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VulkanConst.class)
abstract class MixinVulkanConst {
    @ModifyExpressionValue(method = "textureUsageToVk", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/GpuFormat;hasDepthAspect()Z"), require = 1)
    private static boolean flywheel$stencilAttachmentUsage(boolean hasDepth, @Local(argsOnly = true) GpuFormat format) {
        return hasDepth || format.hasStencilAspect();
    }
}
