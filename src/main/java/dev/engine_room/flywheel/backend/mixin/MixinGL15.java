package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Cleanroom/lwjglxx loads LWJGL3 through the launch classloader so Mixin can transform GL classes.
@Mixin(value = GL15.class, remap = false)
public abstract class MixinGL15 {

    @Inject(method = "glBindBuffer(II)V", at = @At("RETURN"), require = 1)
    private static void flw$onBindBuffer(int target, int buffer, CallbackInfo ci) {
        GlStateTracker._setBuffer(target, buffer);
    }
}
