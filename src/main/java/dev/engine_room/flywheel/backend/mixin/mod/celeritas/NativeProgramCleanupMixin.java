package dev.engine_room.flywheel.backend.mixin.mod.celeritas;

import dev.engine_room.flywheel.backend.engine.NativeChunkOit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.gl.shader.GlProgram", remap = false)
public abstract class NativeProgramCleanupMixin {
    // Native program names can be reused after settings or world reloads.
    @Inject(method = "destroyInternal", at = @At("HEAD"), require = 1)
    private void flw$retire(CallbackInfo ci) { NativeChunkOit.clear(); }
}
