package dev.engine_room.flywheel.api.backend;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * Stand-in for {@code net.minecraft.client.Camera} (1.16+ only); backed by a view entity plus
 * partial tick.
 */
public interface Camera {
    /**
     * The actual rendered camera world position — bakes in third-person back-off, shoulder-cam
     * offset, freecam shift, anything that hooks {@code orientCamera}. Use for frustum culling,
     * distance-based sorting, the {@code flw_cameraPos} shader uniform (fog distance), and any
     * effect that should track the actual rendered viewpoint rather than the player.
     */
    Vec3d getPosition();

    /**
     * Player eye world position (interpolated entity position plus eye height). Use for view-matrix
     * construction ({@code VIEW.translate(-eyePosition)}) and for vanilla-parity submersion checks
     * (vanilla underwater fog triggers on player eye, not on the rendered camera). Distinct from
     * {@link #getPosition()} in third-person and freecam.
     */
    Vec3d eyePosition();

    Vector3f getLookVector();

    float getXRot();

    float getYRot();

    Entity getEntity();

    BlockPos getBlockPosition();
}
