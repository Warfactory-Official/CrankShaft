package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.engine.FarFrontDepth;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTargets.class)
abstract class FarFrontDepthTargetsMixin {
    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void flywheel$closeFrontDepth(CallbackInfo _) {
        FarFrontDepth.close((RenderTargets) (Object) this);
    }
}
