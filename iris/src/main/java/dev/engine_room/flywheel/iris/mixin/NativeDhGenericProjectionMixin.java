package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import dev.engine_room.flywheel.iris.compile.NativeDhProjection;
import net.irisshaders.iris.compat.dh.IrisGenericRenderProgram;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A native-depth pack's DH generic objects take DH's reversed projection.
 */
@Mixin(IrisGenericRenderProgram.class)
abstract class NativeDhGenericProjectionMixin {
    @WrapOperation(method = "bind", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/compat/dh/DHCompat;getProjection(FF)Lorg/joml/Matrix4f;"))
    private Matrix4f flywheel$reversedProjection(float near, float far, Operation<Matrix4f> original) {
        return NativeDepth.active() ? NativeDhProjection.of(near, far) : original.call(near, far);
    }
}
