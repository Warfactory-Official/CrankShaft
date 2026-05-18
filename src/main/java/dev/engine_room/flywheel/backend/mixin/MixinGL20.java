package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GL20.class, remap = false)
public abstract class MixinGL20 {

    @Inject(method = "glUseProgram(I)V", at = @At("RETURN"), require = 1)
    private static void flw$onUseProgram(int program, CallbackInfo ci) {
        GlStateTracker._setProgram(program);
    }
}
