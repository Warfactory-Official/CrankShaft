package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import com.luna.ausm.api.pipeline.shader.ProgramArrayId;
import dev.engine_room.flywheel.backend.engine.shaderpack.AusmReplayBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.PipelineWorldFramebufferFinalization", remap = false)
public abstract class ReplayCompositeMixin {
    // Replay invokes the original single-pass implementations in their validated native order.
    @Inject(method = "runFullscreenPasses(Lcom/luna/ausm/api/pipeline/shader/ProgramArrayId;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void flw$replayArray(ProgramArrayId array, CallbackInfo ci) {
        if (AusmReplayBridge.runAusmArray(this, array)) ci.cancel();
    }
}
