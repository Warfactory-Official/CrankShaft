package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A native-depth pack's reversed shadow pass clears its depth to the far value.
 */
@Mixin(IrisRenderingPipeline.class)
abstract class NativeShadowClearMixin {
    #if MC >= 26.3
    @ModifyArg(method = "beginLevelRendering", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"),
            index = 1)
    #else
    @ModifyArg(method = "beginLevelRendering", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"),
            index = 1)
    #endif
    private double flywheel$reversedShadowClear(double depth) {
        return NativeDepth.active() ? 0.0 : depth;
    }
}
