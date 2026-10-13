package dev.engine_room.flywheel.iris.mixin;

import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.opengl.GlRenderPass;
import dev.engine_room.flywheel.iris.compile.GuestProgram;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
abstract class GlCommandEncoderMeshMixin {
    @Inject(method = "setupDraw", at = @At("TAIL"), require = 1)
    private void flywheel$fontAlbedo(GlRenderPass pass, CallbackInfo ci) {
        if (pass.pipeline.program() instanceof GuestProgram program) program.setupAlbedo(pass.uniforms);
    }

    // Renderpearl and Iris have installed the pass state before the mesh draw replaces the ordinary primitive draw.
    @Inject(method = "executeDraw",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/backend/opengl/GlCommandEncoder;setupDraw(Lcom/mojang/renderpearl/backend/opengl/GlRenderPass;)V",
                    shift = At.Shift.AFTER),
            cancellable = true,
            require = 1)
    private void flywheel$drawMesh(GlRenderPass pass, int baseVertex, int firstIndex, int drawCount,
                                   @Nullable IndexType indexType, int instanceCount, int firstInstance,
                                   CallbackInfo ci) {
        if (pass.pipeline.program() instanceof GuestProgram program && program.meshQuads() != 0) {
            program.drawMeshCommands();
            ci.cancel();
        }
    }
}
