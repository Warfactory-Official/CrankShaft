package dev.engine_room.flywheel.impl.mixin.neoforge;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Initializes every image aspect together; a depth/stencil layout transition cannot omit stencil by default. */
@Mixin(VulkanGpuTexture.class)
abstract class VulkanStencilTextureLayoutMixin {
    @ModifyArg(
            method = "<init>(Lcom/mojang/renderpearl/backend/vulkan/VulkanDevice;ILjava/lang/String;Lcom/mojang/renderpearl/api/GpuFormat;IIII)V",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkImageSubresourceRange;aspectMask(I)Lorg/lwjgl/vulkan/VkImageSubresourceRange;"),
            index = 0)
    private int flywheel$initializeAllImageAspects(int aspectMask) {
        return VulkanConst.formatAspectMask(((GpuTexture) (Object) this).getFormat());
    }
}
