package dev.engine_room.flywheel.backend.engine.far;

import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL45C;

/**
 * Explicit producer view. The matrix maps render-origin-relative world coordinates to the target's raw clip
 * coordinates. Camera is absolute world position; the origin is shared with the view matrix. Callers own the
 * target, depth snapshots, OIT state and complementary host/main coverage predicate. Render thread, borrowed for
 * one submission.
 */
public record FarLayerView(Matrix4fc viewProjection, Vec3 camera, Vec3i renderOrigin,
                           int width, int height, boolean zeroToOne, boolean reverseZ,
                           boolean hardwareZeroToOne) {
    /**
     * Captures the host's hardware clip convention once at a view boundary, never once per brick.
     */
    public FarLayerView(Matrix4fc viewProjection, Vec3 camera, Vec3i renderOrigin,
                        int width, int height, boolean zeroToOne, boolean reverseZ) {
        this(viewProjection, camera, renderOrigin, width, height, zeroToOne, reverseZ,
                GL11C.glGetInteger(GL45C.GL_CLIP_DEPTH_MODE)
                        == GL45C.GL_ZERO_TO_ONE);
    }
}
