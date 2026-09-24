package dev.engine_room.flywheel.impl.mixin.sodium;

import dev.engine_room.flywheel.impl.compat.NvidiumCompat;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Compat with Nvidium: evaluated per call, unlike an enabled provider, which Sodium caches until a dependency changes.
@Mixin(Option.class)
abstract class SodiumOptionMixin {
    @Shadow
    @Final
    Identifier id;

    @Inject(method = "isEnabled", at = @At("HEAD"), cancellable = true, require = 1)
    private void flywheel$greyNvidium(CallbackInfoReturnable<Boolean> cir) {
        if (NvidiumCompat.isNvidiumOption(id) && NvidiumCompat.engineOwnsTerrain()) {
            cir.setReturnValue(false);
        }
    }
}
