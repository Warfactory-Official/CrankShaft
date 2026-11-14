package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(IntermediaryShaderModule.class)
abstract class VulkanShaderInputMixin {
    @ModifyArg(method = "rebind", at = @At(value = "INVOKE",
            target = "Ljava/nio/IntBuffer;put(II)Ljava/nio/IntBuffer;", ordinal = 0), index = 1, require = 1)
    private int flywheel$preserveUnusedInputSlots(int _, @Local(ordinal = 1) int inputIndex) {
        return inputIndex;
    }
}
