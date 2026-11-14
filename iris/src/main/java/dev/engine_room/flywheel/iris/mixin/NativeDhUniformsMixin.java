package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.NativeDepth;
import dev.engine_room.flywheel.iris.compile.NativeDhProjection;
import net.irisshaders.iris.uniforms.MatrixUniforms;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Supplier;

/**
 * A native-depth pack's {@code dhProjection} family is reversed. {@code DHCompat.getProjection} stays forward for
 * Iris's own culling.
 */
@Mixin(MatrixUniforms.class)
abstract class NativeDhUniformsMixin {
    @ModifyArg(method = "addMatrixUniforms", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/uniforms/MatrixUniforms;addDHMatrix(Lnet/irisshaders/iris/gl/uniform/UniformHolder;Ljava/lang/String;Ljava/util/function/Supplier;)V"),
            index = 2)
    private static Supplier<Matrix4fc> flywheel$reversedProjection(Supplier<Matrix4fc> forward) {
        return NativeDepth.active() ? NativeDhProjection::uniform : forward;
    }
}
