package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.engine.ExperimentalEngine;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShadowRenderer.class)
abstract class ExperimentalShadowRendererMixin {
    @Inject(method = "renderShadows", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;copyPreTranslucentDepth(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;)V",
            shift = At.Shift.AFTER))
    private void flywheel$translucentGuestShadows(CallbackInfo ci) {
        ExperimentalEngine.afterPreTranslucentDepth();
    }
}
