package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GL30.class, remap = false)
public abstract class MixinGL30 {

    @Inject(method = "glBindVertexArray(I)V", at = @At("RETURN"), require = 1)
    private static void flw$onBindVertexArray(int array, CallbackInfo ci) {
        GlStateTracker._setVertexArray(array);
    }
}
