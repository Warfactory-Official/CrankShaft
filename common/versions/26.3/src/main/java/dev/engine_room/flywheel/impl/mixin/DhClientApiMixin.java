package dev.engine_room.flywheel.impl.mixin;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientApi.class)
abstract class DhClientApiMixin {
    // Compat with DH 3.3.4: the old layer hook runs inside Solid; even its queued uploads need a closed pass.
    @Inject(method = "renderLods()V", at = @At("HEAD"), cancellable = true)
    private void flywheel$skipLegacyOpaque(CallbackInfo ci) {
        if (DistantHorizonsCompat.solidTerrainPhase()) ci.cancel();
    }
}
