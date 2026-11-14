package dev.engine_room.flywheel.iris.experimental.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Keep both framebuffer caches coherent when changing the read and draw targets together.
 */
@Mixin(GlStateManager.class)
abstract class ExperimentalGlFramebufferBindMixin {
    @Shadow
    public static int readFbo;
    @Shadow
    public static int writeFbo;

    @WrapMethod(method = "_glBindFramebuffer")
    private static void flywheel$combinedBind(int target, int framebuffer, Operation<Void> original) {
        if (target != GL30C.GL_FRAMEBUFFER || readFbo == framebuffer || writeFbo == framebuffer) {
            original.call(target, framebuffer);
            return;
        }
        GL30C.glBindFramebuffer(target, framebuffer);
        readFbo = framebuffer;
        writeFbo = framebuffer;
    }
}
