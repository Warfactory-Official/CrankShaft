package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import dev.engine_room.flywheel.backend.engine.shaderpack.AusmReplayBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.PipelineFullscreenPassRendering", remap = false)
public abstract class ReplayLifecycleMixin {
    // Replay borrows native targets; restore and release its resources before the host deletes them.
    @Inject(method = "cleanupRuntimeState(ZZZ)V", at = @At("HEAD"), require = 1)
    private void flw$releaseReplay(boolean activePrograms, boolean cachedPrograms, boolean terrain, CallbackInfo ci) {
        AusmReplayBridge.closeAusm();
    }
}
