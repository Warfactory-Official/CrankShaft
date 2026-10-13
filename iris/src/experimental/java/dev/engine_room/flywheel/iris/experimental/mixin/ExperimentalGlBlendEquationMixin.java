package dev.engine_room.flywheel.iris.experimental.mixin;

import com.mojang.renderpearl.backend.opengl.GlConst;
import dev.engine_room.flywheel.iris.experimental.ExperimentalBlendEquations;
import net.irisshaders.iris.backend.PipelineState;
import org.lwjgl.opengl.GL14C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.irisshaders.iris.opengl.GlGraphicsShader$Pipeline")
abstract class ExperimentalGlBlendEquationMixin {
    @Shadow
    @Final
    private PipelineState state;
    @Unique
    private int flywheel$colorEquation = GL14C.GL_FUNC_ADD;
    @Unique
    private int flywheel$alphaEquation = GL14C.GL_FUNC_ADD;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$carrierEquations(CallbackInfo ci) {
        var blend = ExperimentalBlendEquations.function(state.vanillaPipeline(), 0);
        if (blend != null) {
            flywheel$colorEquation = GlConst.toGl(blend.color().op());
            flywheel$alphaEquation = GlConst.toGl(blend.alpha().op());
        }
    }

    @ModifyArg(method = "applyState", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_blendEquationSeparate(II)V"), index = 0)
    private int flywheel$colorEquation(int original) {
        return flywheel$colorEquation;
    }

    @ModifyArg(method = "applyState", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_blendEquationSeparate(II)V"), index = 1)
    private int flywheel$alphaEquation(int original) {
        return flywheel$alphaEquation;
    }
}
