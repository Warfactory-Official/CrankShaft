package dev.engine_room.flywheel.impl.mixin;

import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import dev.engine_room.flywheel.impl.far.DhFarSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FullDataSourceProviderV2.class)
abstract class DhFarSourceLifecycleMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void flywheel$finishFarReads(CallbackInfo _) {
        DhFarSource.providerClosing((FullDataSourceProviderV2) (Object) this);
    }
}
