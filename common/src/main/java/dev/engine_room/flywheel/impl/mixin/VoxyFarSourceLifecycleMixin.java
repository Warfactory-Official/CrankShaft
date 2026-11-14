package dev.engine_room.flywheel.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.engine_room.flywheel.impl.far.VoxyFarSource;
import me.cortex.voxy.commonImpl.VoxyInstance;

@Mixin(VoxyInstance.class)
abstract class VoxyFarSourceLifecycleMixin implements VoxyFarSource.InstanceAccess {
    @Unique
    private volatile boolean flywheel$farSourcesClosing;

    @Override
    public boolean flywheel$farSourcesClosing() {
        return flywheel$farSourcesClosing;
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void flywheel$closeFarSources(CallbackInfo _) {
        flywheel$farSourcesClosing = true;
        VoxyFarSource.closeInstance((VoxyInstance) (Object) this);
    }
}
