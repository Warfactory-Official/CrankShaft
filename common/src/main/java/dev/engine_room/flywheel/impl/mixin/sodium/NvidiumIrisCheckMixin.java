package dev.engine_room.flywheel.impl.mixin.sodium;

import dev.engine_room.flywheel.impl.compat.NvidiumCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Compat with Nvidium: part of its enable decision, taken whenever Sodium creates a {@code RenderSectionManager};
 * {@code false} keeps Nvidium off for that world renderer.
 */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.sodiumCompat.IrisCheck")
abstract class NvidiumIrisCheckMixin {
    @Inject(method = "checkIrisShouldDisable", at = @At("RETURN"), cancellable = true, require = 1)
    private static void flywheel$yieldTerrain(CallbackInfoReturnable<Boolean> cir) {
        if (NvidiumCompat.engineOwnsTerrain()) {
            cir.setReturnValue(false);
        }
    }
}
