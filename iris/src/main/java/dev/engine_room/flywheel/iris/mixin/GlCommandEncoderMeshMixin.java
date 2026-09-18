package dev.engine_room.flywheel.iris.mixin;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import dev.engine_room.flywheel.iris.compile.GuestProgram;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlCommandEncoder")
abstract class GlCommandEncoderMeshMixin {
    // trySetup and validation have already run here. A mesh-linked program cannot be primed with an indexed draw,
    // even a zero-count one; replace the actual draw while retaining the encoder's framebuffer/uniform ownership.
    @Inject(method = "drawFromBuffers", at = @At("HEAD"), cancellable = true, require = 1)
    private void flywheel$drawMesh(GlRenderPass pass, int baseVertex, int firstIndex, int drawCount,
                                   @Nullable IndexType indexType, GlRenderPipeline pipeline,
                                   int instanceCount, int firstInstance, CallbackInfo ci) {
        if (pipeline.program() instanceof GuestProgram program && program.meshQuads() != 0) {
            program.drawMeshCommands();
            ci.cancel();
        }
    }
}
