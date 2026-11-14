package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import dev.engine_room.flywheel.iris.compile.NativeDhProjection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A native-depth pack's DH terrain and water programs take reversed projections; the shadow one stays raw.
 */
@Mixin(targets = "net.irisshaders.iris.compat.dh.LodRendererEvents$13")
abstract class NativeDhPassProjectionMixin {
    @WrapOperation(method = "beforeRender", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/helpers/MatrixUtils;toMinusOneToOne(Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;"))
    private Matrix4f flywheel$rawShadow(Matrix4f projection, Operation<Matrix4f> original) {
        return NativeDepth.active() ? projection : original.call(projection);
    }

    @WrapOperation(method = "beforeRender", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/compat/dh/DHCompat;getProjection(FF)Lorg/joml/Matrix4f;"))
    private Matrix4f flywheel$reversedMain(float near, float far, Operation<Matrix4f> original) {
        return NativeDepth.active() ? NativeDhProjection.of(near, far) : original.call(near, far);
    }
}
