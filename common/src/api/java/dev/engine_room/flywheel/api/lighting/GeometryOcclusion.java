package dev.engine_room.flywheel.api.lighting;

import net.minecraft.core.Vec3i;
import org.joml.Matrix4fc;

/**
 * A world's shared scene of opt-in ambient occluders. Registering geometry never draws it.
 * Both loaders consume the same meshes and world-space poses; each handle belongs to one visual and
 * must be deleted with it. Handles may be changed by their owning visual's frame/tick update, but not
 * concurrently with that same handle's update. The backend reads them after visual updates finish.
 * Registered off-screen parts still occlude; contributors keep their relevant poses current even when
 * their draw is culled. Receiver subscriptions supply surrounding compiled terrain, including
 * chunk-rendered machine bases. Other entity/visual draws require explicit registration.
 */
public interface GeometryOcclusion {
    Occluder add(OcclusionMesh mesh, Vec3i worldAnchor, Matrix4fc localPose);

    /**
     * An occluder pose is relative to an integer world anchor, including any embedding transforms.
     * The mesh is immutable; replacing geometry requires a new handle. Pose changes do not rebuild it.
     */
    interface Occluder {
        void transform(Vec3i worldAnchor, Matrix4fc localPose);

        /**
         * Per-handle cutout UV and alpha multiplier; change before scene preparation.
         */
        default void coverage(float scaleU, float scaleV, float offsetU, float offsetV, float alpha) {
            coverage(scaleU, 0, 0, scaleV, offsetU, offsetV, alpha);
        }

        /**
         * Full affine UV remap: rows (m00,m01) and (m10,m11), then offset and alpha multiplier.
         */
        void coverage(float m00, float m01, float m10, float m11, float offsetU, float offsetV, float alpha);

        /**
         * Keep min <= dot(normal, sourcePosition) <= max. The interval is in immutable mesh-local
         * coordinates, before the occluder pose; callers account for any draw-instance slide here.
         * Infinite endpoints express half-spaces; min > max hides the surface. Updates change GPU parameters, not the mesh.
         */
        void clip(float normalX, float normalY, float normalZ, float min, float max);

        default void clearClip() {
            clip(0, 0, 0, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
        }

        void delete();
    }
}
