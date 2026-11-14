package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.shadows.ShadowMatrices;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static net.irisshaders.iris.shadows.ShadowMatrices.FAR;
import static net.irisshaders.iris.shadows.ShadowMatrices.NEAR;

/**
 * A native-depth pack's shadow pass is reversed like vanilla's level projection: near and far swap.
 */
@Mixin(ShadowMatrices.class)
abstract class NativeShadowMatricesMixin {
    @WrapOperation(method = "createOrthoMatrix", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4f;setOrthoSymmetric(FFFFZ)Lorg/joml/Matrix4f;"))
    private static Matrix4f flywheel$reversedOrtho(Matrix4f matrix, float width, float height, float near, float far,
                                                   boolean zZeroToOne, Operation<Matrix4f> original) {
        return NativeDepth.active() ? original.call(matrix, width, height, far, near, zZeroToOne)
                : original.call(matrix, width, height, near, far, zZeroToOne);
    }

    @ModifyReturnValue(method = "createPerspectiveMatrix", at = @At("RETURN"))
    private static Matrix4f flywheel$reversedPerspective(Matrix4f forward) {
        if (!NativeDepth.active()) return forward;
        boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        return forward.m22((zZeroToOne ? NEAR : NEAR + FAR) / (FAR - NEAR))
                      .m32((zZeroToOne ? NEAR : 2.0F * NEAR) * FAR / (FAR - NEAR));
    }
}
