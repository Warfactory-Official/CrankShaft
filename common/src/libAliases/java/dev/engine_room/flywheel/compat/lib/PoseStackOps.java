package dev.engine_room.flywheel.compat.lib;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Quaternionfc;

/** Expands to the native pose rotation calls for the selected Minecraft version. */
public macro class PoseStackOps {
    void rotate(PoseStack stack, Quaternionfc rotation) {
        #if MC >= 26.3
        stack.rotate(rotation);
        #else
        stack.mulPose(rotation);
        #endif
    }

    void rotateRadians(PoseStack stack, Axis axis, float radians) {
        #if MC >= 26.3
        stack.rotate(axis, radians);
        #else
        stack.mulPose(axis.rotation(radians));
        #endif
    }

    void rotateDegrees(PoseStack stack, Axis axis, float degrees) {
        #if MC >= 26.3
        stack.rotateDegrees(axis, degrees);
        #else
        stack.mulPose(axis.rotationDegrees(degrees));
        #endif
    }
}
