package dev.engine_room.flywheel.backend.mixin;

import com.mojang.blaze3d.opengl.GlStateManager;
import dev.engine_room.flywheel.backend.gl.GlStateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2: Mojang RHI binds through GlStateManager behind flywheel's back; reconcile the tracker
// cache so bind-skip guards don't elide needed re-binds.
// remap=false: blaze3d names survive unchanged on both loaders.
@Mixin(value = GlStateManager.class, remap = false)
abstract class GlStateManagerMixin {
    @Inject(method = "_glBindBuffer(II)V", at = @At("RETURN"), require = 1)
    private static void flywheel$onBindBuffer(int target, int buffer, CallbackInfo ci) {
        GlStateTracker._setBuffer(target, buffer);
    }

    @Inject(method = "_glBindVertexArray(I)V", at = @At("RETURN"), require = 1)
    private static void flywheel$onBindVertexArray(int array, CallbackInfo ci) {
        GlStateTracker._setVertexArray(array);
    }

    @Inject(method = "_glUseProgram(I)V", at = @At("RETURN"), require = 1)
    private static void flywheel$onUseProgram(int program, CallbackInfo ci) {
        GlStateTracker._setProgram(program);
        GlStateTracker.invalidateEncoderProgramCache();
    }

    @Inject(method = "_glDeleteBuffers(I)V", at = @At("RETURN"), require = 1)
    private static void flywheel$onDeleteBuffers(int buffer, CallbackInfo ci) {
        GlStateTracker._onBufferDeleted(buffer);
    }
}
