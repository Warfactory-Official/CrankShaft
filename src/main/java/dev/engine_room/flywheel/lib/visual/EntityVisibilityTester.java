package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.lib.math.MoreMath;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3i;
import org.joml.FrustumIntersection;
import org.jspecify.annotations.Nullable;

/**
 * A helper class for testing whether an Entity is visible.
 * <p>
 * The last visible AABB is also checked to prevent the Entity from freezing when it goes offscreen.
 */
public class EntityVisibilityTester {
    private final Entity entity;
    private final Vec3i renderOrigin;
    private final float scale;
    @Nullable
    private AxisAlignedBB lastVisibleAABB;

    /**
     * Create a new EntityVisibilityTester.
     *
     * @param entity       The Entity to test.
     * @param renderOrigin The render origin according to the VisualizationContext.
     * @param scale        Multiplier for the Entity's size, can be used to adjust for when
     *                     an entity's model is larger than its hitbox.
     */
    public EntityVisibilityTester(Entity entity, Vec3i renderOrigin, float scale) {
        this.entity = entity;
        this.renderOrigin = renderOrigin;
        this.scale = scale;
    }

    /**
     * Check whether the Entity is visible.
     *
     * @param frustum The frustum to test against.
     * @return {@code true} if the Entity is visible, {@code false} otherwise.
     */
    public boolean check(FrustumIntersection frustum) {
        AxisAlignedBB aabb = entity.getEntityBoundingBox();

        // If we've never seen the entity before assume its visible.
        // Fixes entities freezing when they first spawn.
        boolean visible = lastVisibleAABB == null;

        if (!visible) {
            visible = adjustAndTestAABB(frustum, aabb);
        }

        if (!visible && lastVisibleAABB != aabb) {
            // If the entity isn't visible, check the last visible AABB as well.
            // This is to avoid Entities freezing when the go offscreen.
            visible = adjustAndTestAABB(frustum, lastVisibleAABB);
        }

        if (visible) {
            lastVisibleAABB = aabb;
        }
        return visible;
    }

    private boolean adjustAndTestAABB(FrustumIntersection frustum, AxisAlignedBB aabb) {
        float x = (float) ((aabb.minX + aabb.maxX) * 0.5) - renderOrigin.getX();
        float y = (float) ((aabb.minY + aabb.maxY) * 0.5) - renderOrigin.getY();
        float z = (float) ((aabb.minZ + aabb.maxZ) * 0.5) - renderOrigin.getZ();
        float maxSize = (float) Math.max(aabb.maxX - aabb.minX, Math.max(aabb.maxY - aabb.minY, aabb.maxZ - aabb.minZ));
        return frustum.testSphere(x, y, z, maxSize * MoreMath.SQRT_3_OVER_2 * scale);
    }
}
