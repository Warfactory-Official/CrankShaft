package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.uniforms.MatrixUniforms;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A native-depth pack's {@code shadowProjection} family is the reversed shadow projection itself.
 */
@Mixin(MatrixUniforms.class)
abstract class NativeShadowUniformsMixin {
    @WrapOperation(method = "lambda$addMatrixUniforms$1", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/helpers/MatrixUtils;toMinusOneToOne(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;"))
    private static Matrix4f flywheel$rawShadowProjection(Matrix4f projection, Operation<Matrix4f> original) {
        return NativeDepth.active() ? projection : original.call(projection);
    }
}
