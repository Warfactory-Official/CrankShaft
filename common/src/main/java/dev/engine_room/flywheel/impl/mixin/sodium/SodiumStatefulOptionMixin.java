package dev.engine_room.flywheel.impl.mixin.sodium;

import dev.engine_room.flywheel.impl.compat.NvidiumCompat;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StatefulOption.class)
abstract class SodiumStatefulOptionMixin {
    @Inject(method = "getTooltip", at = @At("HEAD"), cancellable = true, require = 1)
    private void flywheel$explainNvidium(CallbackInfoReturnable<Component> cir) {
        if (NvidiumCompat.isNvidiumOption(((SodiumOptionAccessor) (Object) this).flywheel$id())
                && NvidiumCompat.engineOwnsTerrain()) {
            cir.setReturnValue(NvidiumCompat.terrainOwnedTooltip());
        }
    }
}
