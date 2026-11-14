package dev.engine_room.flywheel.iris.mixin;

#if MC >= 26.3
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
#else
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.pipeline.RenderPipeline;
#endif
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A native-depth pack's reversed shadow pass keeps every pipeline's own depth compare.
 */
#if MC >= 26.3
        @Mixin(GlRenderPipeline.class)
#else
        @Mixin(GlCommandEncoder.class)
#endif
        abstract class NativeShadowCompareMixin {
    #if MC >= 26.3
    // Compat with Iris 1.11.7: its unconditional shadow flip is an involution; flipping again cancels it in any order.
    @WrapOperation(method = "bind", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_depthFunc(I)V"))
    private void flywheel$ownCompare(int func, Operation<Void> original) {
        original.call(ShadowRenderingState.areShadowsCurrentlyBeingRendered() && NativeDepth.active()
                ? flywheel$flip(func) : func);
    }
    #else
    // Compat with Iris 1.11.4: it flips only its own pipelines and skips inner wrappers; undo its flip on the result.
    @ModifyExpressionValue(method = "applyPipelineState", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/opengl/GlConst;toGl(Lcom/mojang/blaze3d/platform/CompareOp;)I"))
    private int flywheel$ownCompare(int func, @Local(argsOnly = true) RenderPipeline pipeline) {
        if (!ShadowRenderingState.areShadowsCurrentlyBeingRendered() || !NativeDepth.active()) return func;
        int own = GlConst.toGl(pipeline.getDepthStencilState().depthTest());
        return func == flywheel$flip(own) ? own : func;
    }
    #endif

    @Unique
    private static int flywheel$flip(int func) {
        return switch (func) {
            case GL11C.GL_LESS -> GL11C.GL_GREATER;
            case GL11C.GL_LEQUAL -> GL11C.GL_GEQUAL;
            case GL11C.GL_GREATER -> GL11C.GL_LESS;
            case GL11C.GL_GEQUAL -> GL11C.GL_LEQUAL;
            default -> func;
        };
    }
}
