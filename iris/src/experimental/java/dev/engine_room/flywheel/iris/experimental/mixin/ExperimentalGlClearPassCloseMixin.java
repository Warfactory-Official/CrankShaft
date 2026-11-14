package dev.engine_room.flywheel.iris.experimental.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.opengl.GlCommandEncoder;
import dev.engine_room.flywheel.iris.experimental.GlClearSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GlCommandEncoder.class)
abstract class ExperimentalGlClearPassCloseMixin {
    @WrapOperation(method = "submitRenderPass", at = @At(value = "INVOKE", target =
            "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_glBindFramebuffer(II)V"))
    private void flywheel$deferClearUnbind(int target, int framebuffer, Operation<Void> original) {
        if (GlClearSequence.active) GlClearSequence.pendingUnbind = true;
        else original.call(target, framebuffer);
    }
}
