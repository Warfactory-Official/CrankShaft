package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.engine.GuestShadows;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translucent guest casters after the pre-translucent depth copy, as translucent terrain: Iris's shadow render callback
 * runs before it, where they would land in {@code shadowtex1} as opaque casters.
 */
@Mixin(ShadowRenderer.class)
abstract class ShadowRendererMixin {
    @Inject(method = "renderShadows", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;copyPreTranslucentDepth(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;)V",
            shift = At.Shift.AFTER))
    private void flywheel$guestTranslucentShadows(CallbackInfo ci) {
        GuestShadows.afterPreTranslucentDepth();
    }
}
