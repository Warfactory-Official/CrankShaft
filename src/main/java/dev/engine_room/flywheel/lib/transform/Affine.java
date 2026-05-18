package dev.engine_room.flywheel.lib.transform;

import net.minecraft.util.EnumFacing;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

public interface Affine<Self extends Affine<Self>> extends Translate<Self>, Rotate<Self>, Scale<Self> {
    default Self rotateAround(Quaternionfc quaternion, float x, float y, float z) {
        return translate(x, y, z).rotate(quaternion)
                .translateBack(x, y, z);
    }

    default Self rotateAround(Quaternionfc quaternion, Vector3fc vec) {
        return rotateAround(quaternion, vec.x(), vec.y(), vec.z());
    }

    default Self rotateCentered(Quaternionfc q) {
        return rotateAround(q, CENTER, CENTER, CENTER);
    }

    default Self rotateCentered(float radians, float axisX, float axisY, float axisZ) {
        if (radians == 0) {
            return self();
        }
        return rotateCentered(new Quaternionf().setAngleAxis(radians, axisX, axisY, axisZ));
    }

    default Self rotateCentered(float radians, Vector3fc axis) {
        return rotateCentered(radians, axis.x(), axis.y(), axis.z());
    }

    default Self rotateCentered(float radians, EnumFacing.Axis axis) {
        return switch (axis) {
            case X -> rotateXCentered(radians);
            case Y -> rotateYCentered(radians);
            case Z -> rotateZCentered(radians);
        };
    }

    default Self rotateCentered(float radians, EnumFacing axis) {
        return rotateCentered(radians, axis.getXOffset(), axis.getYOffset(), axis.getZOffset());
    }

    default Self rotateCenteredDegrees(float degrees, float axisX, float axisY, float axisZ) {
        return rotateCentered(DEG_TO_RAD * degrees, axisX, axisY, axisZ);
    }

    default Self rotateCenteredDegrees(float degrees, Vector3fc axis) {
        return rotateCentered(DEG_TO_RAD * degrees, axis);
    }

    default Self rotateCenteredDegrees(float degrees, EnumFacing axis) {
        return rotateCentered(DEG_TO_RAD * degrees, axis);
    }

    default Self rotateCenteredDegrees(float degrees, EnumFacing.Axis axis) {
        return rotateCentered(DEG_TO_RAD * degrees, axis);
    }

    default Self rotateXCentered(float radians) {
        return translate(CENTER, CENTER, CENTER).rotateX(radians).translateBack(CENTER, CENTER, CENTER);
    }

    default Self rotateYCentered(float radians) {
        return translate(CENTER, CENTER, CENTER).rotateY(radians).translateBack(CENTER, CENTER, CENTER);
    }

    default Self rotateZCentered(float radians) {
        return translate(CENTER, CENTER, CENTER).rotateZ(radians).translateBack(CENTER, CENTER, CENTER);
    }

    default Self rotateXCenteredDegrees(float degrees) {
        return rotateXCentered(DEG_TO_RAD * degrees);
    }

    default Self rotateYCenteredDegrees(float degrees) {
        return rotateYCentered(DEG_TO_RAD * degrees);
    }

    default Self rotateZCenteredDegrees(float degrees) {
        return rotateZCentered(DEG_TO_RAD * degrees);
    }
}
