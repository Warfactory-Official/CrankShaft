package dev.engine_room.flywheel.lib.transform;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

/**
 * 1.12.2: {@code PoseStack} does not exist. The {@code transform(PoseStack)} /
 * {@code transform(PoseStack.Pose)} overloads are dropped — consumers can call
 * {@code transform(Matrix4fc, Matrix3fc)} directly with their own matrices.
 */
public interface Transform<Self extends Transform<Self>> extends Affine<Self> {
    Self mulPose(Matrix4fc pose);

    Self mulNormal(Matrix3fc normal);

    default Self transform(Matrix4fc pose, Matrix3fc normal) {
        return mulPose(pose).mulNormal(normal);
    }
}
