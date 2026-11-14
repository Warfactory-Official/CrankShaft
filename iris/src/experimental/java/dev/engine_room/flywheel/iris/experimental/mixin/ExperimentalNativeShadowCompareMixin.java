package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.backend.PipelineState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A native-depth pack's reversed shadow pass keeps every pipeline's own depth compare and bias, on both backends.
 */
@Mixin(PipelineState.class)
abstract class ExperimentalNativeShadowCompareMixin {
    @ModifyVariable(method = "depthState(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;ZZ)Lcom/mojang/renderpearl/api/pipeline/DepthStencilState;",
            at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static boolean flywheel$forwardShadow(boolean shadow) {
        return shadow && !NativeDepth.active();
    }
}
