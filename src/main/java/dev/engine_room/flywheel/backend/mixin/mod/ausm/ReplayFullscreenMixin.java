package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import dev.engine_room.flywheel.backend.engine.shaderpack.AusmReplayBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.render.FullscreenQuad", remap = false)
public abstract class ReplayFullscreenMixin {
    // Native sampler/uniform setup has completed; only the draw is replaced by its GPU layer gate.
    @Inject(method = "draw", at = @At("HEAD"), cancellable = true, require = 1)
    private static void flw$replayDraw(CallbackInfo ci) {
        if (AusmReplayBridge.graphics()) ci.cancel();
    }
}
