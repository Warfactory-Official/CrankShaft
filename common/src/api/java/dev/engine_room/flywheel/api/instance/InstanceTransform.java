package dev.engine_room.flywheel.api.instance;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The affine mesh-position transform used by an instance's vertex shader, before its embedding.
 */
@FunctionalInterface
public interface InstanceTransform {
    void write(long instance, Matrix4fc view, Matrix4f pose);

    default boolean viewDependent() {
        return false;
    }
}
