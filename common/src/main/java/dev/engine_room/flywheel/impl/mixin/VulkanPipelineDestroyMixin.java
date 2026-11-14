package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(VulkanRenderPipeline.class)
abstract class VulkanPipelineDestroyMixin {
    @Shadow
    @Final
    private long withoutDepthPipeline;

    @ModifyExpressionValue(method = "destroy", at = @At(value = "FIELD", target =
            #if MC >= 26.3
            "Lcom/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline;withDepthPipeline:J",
            #else
            "Lcom/mojang/blaze3d/vulkan/VulkanRenderPipeline;withDepthPipeline:J",
            #endif
            opcode = Opcodes.GETFIELD, ordinal = 0), require = 1)
    private long flywheel$includeDepthlessOwner(long withDepth) {
        return withDepth != 0 ? withDepth : withoutDepthPipeline;
    }
}
