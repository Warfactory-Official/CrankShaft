package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.engine.ExperimentalEngine;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShadowRenderer.class)
abstract class ExperimentalShadowRendererMixin {
    // The opaque depth snapshot must exclude translucent guest casters.
    @Inject(method = "copyPreTranslucentDepth", at = @At("RETURN"))
    private void flywheel$translucentGuestShadows(CallbackInfo _) {
        ExperimentalEngine.afterPreTranslucentDepth();
    }
}
