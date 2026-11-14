package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A native-depth pack's {@code iris_ProjMatInverse} inverts the raw level or reversed shadow projection.
 */
@Mixin(ExtendedShader.class)
abstract class NativeProjectionInverseMixin {
    @WrapOperation(method = "iris$setupState", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/helpers/MatrixUtils;undoRevZ(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;"))
    private Matrix4f flywheel$rawShadow(Matrix4f projection, Operation<Matrix4f> original) {
        return NativeDepth.active() ? projection : original.call(projection);
    }

    @ModifyExpressionValue(method = "iris$setupState", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/uniforms/CapturedRenderingState;getGbufferProjection()Lorg/joml/Matrix4fc;"))
    private Matrix4fc flywheel$rawLevel(Matrix4fc forward) {
        return NativeDepth.active() ? NativeDepth.levelProjection() : forward;
    }
}
