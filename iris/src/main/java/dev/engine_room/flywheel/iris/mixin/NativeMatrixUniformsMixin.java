package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.uniforms.MatrixUniforms;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Supplier;

/**
 * A native-depth pack's {@code gbufferProjection} family is the level projection itself. The captured forward matrix
 * stays as it is for Iris's own culling.
 */
@Mixin(MatrixUniforms.class)
abstract class NativeMatrixUniformsMixin {
    @ModifyArg(method = "addMatrixUniforms", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/uniforms/MatrixUniforms;addMatrix(Lnet/irisshaders/iris/gl/uniform/UniformHolder;Ljava/lang/String;Ljava/util/function/Supplier;)V",
            ordinal = 1), index = 2)
    private static Supplier<Matrix4fc> flywheel$rawProjection(Supplier<Matrix4fc> forward) {
        return NativeDepth.active() ? NativeDepth::levelProjection : forward;
    }
}
