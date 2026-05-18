package dev.engine_room.flywheel.lib.transform;

import net.minecraft.util.EnumFacing;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

public interface Rotate<Self extends Rotate<Self>> {
    float DEG_TO_RAD = (float) (Math.PI / 180.0);

    Self rotate(Quaternionfc quaternion);

    default Self rotate(AxisAngle4f axisAngle) {
        return rotate(new Quaternionf(axisAngle));
    }

    default Self rotate(float radians, float axisX, float axisY, float axisZ) {
        if (radians == 0) {
            return self();
        }
        return rotate(new Quaternionf().setAngleAxis(radians, axisX, axisY, axisZ));
    }

    default Self rotate(float radians, Vector3fc axis) {
        return rotate(radians, axis.x(), axis.y(), axis.z());
    }

    default Self rotate(float radians, EnumFacing axis) {
        return rotate(radians, axis.getXOffset(), axis.getYOffset(), axis.getZOffset());
    }

    default Self rotate(float radians, EnumFacing.Axis axis) {
        return switch (axis) {
            case X -> rotateX(radians);
            case Y -> rotateY(radians);
            case Z -> rotateZ(radians);
        };
    }

    default Self rotateDegrees(float degrees, float axisX, float axisY, float axisZ) {
        return rotate(DEG_TO_RAD * degrees, axisX, axisY, axisZ);
    }

    default Self rotateDegrees(float degrees, Vector3fc axis) {
        return rotate(DEG_TO_RAD * degrees, axis);
    }

    default Self rotateDegrees(float degrees, EnumFacing axis) {
        return rotate(DEG_TO_RAD * degrees, axis);
    }

    default Self rotateDegrees(float degrees, EnumFacing.Axis axis) {
        return rotate(DEG_TO_RAD * degrees, axis);
    }

    default Self rotateX(float radians) {
        return rotate(radians, 1f, 0f, 0f);
    }

    default Self rotateY(float radians) {
        return rotate(radians, 0f, 1f, 0f);
    }

    default Self rotateZ(float radians) {
        return rotate(radians, 0f, 0f, 1f);
    }

    default Self rotateXDegrees(float degrees) {
        return rotateX(DEG_TO_RAD * degrees);
    }

    default Self rotateYDegrees(float degrees) {
        return rotateY(DEG_TO_RAD * degrees);
    }

    default Self rotateZDegrees(float degrees) {
        return rotateZ(DEG_TO_RAD * degrees);
    }

    default Self rotateToFace(EnumFacing facing) {
        return switch (facing) {
            case DOWN -> rotateXDegrees(-90);
            case UP -> rotateXDegrees(90);
            case NORTH -> self();
            case SOUTH -> rotateYDegrees(180);
            case WEST -> rotateYDegrees(90);
            case EAST -> rotateYDegrees(270);
        };
    }

    default Self rotateTo(float fromX, float fromY, float fromZ, float toX, float toY, float toZ) {
        return rotate(new Quaternionf().rotationTo(fromX, fromY, fromZ, toX, toY, toZ));
    }

    default Self rotateTo(Vector3fc from, Vector3fc to) {
        return rotateTo(from.x(), from.y(), from.z(), to.x(), to.y(), to.z());
    }

    default Self rotateTo(EnumFacing from, EnumFacing to) {
        return rotateTo(from.getXOffset(), from.getYOffset(), from.getZOffset(), to.getXOffset(), to.getYOffset(), to.getZOffset());
    }

    @SuppressWarnings("unchecked")
    default Self self() {
        return (Self) this;
    }
}
