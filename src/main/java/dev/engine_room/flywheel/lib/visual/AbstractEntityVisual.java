package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.visual.EntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.FlatLit;
import dev.engine_room.flywheel.lib.util.LevelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The layer between an {@link Entity} and the Flywheel backend.
 * <br>
 * <br> There are a few additional features that overriding classes can opt in to:
 * <ul>
 *     <li>{@link dev.engine_room.flywheel.api.visual.DynamicVisual}</li>
 *     <li>{@link dev.engine_room.flywheel.api.visual.TickableVisual}</li>
 *     <li>{@link dev.engine_room.flywheel.api.visual.LightUpdatedVisual}</li>
 *     <li>{@link dev.engine_room.flywheel.api.visual.ShaderLightVisual}</li>
 * </ul>
 * See the interfaces' documentation for more information about each one.
 *
 * <br> Implementing one or more of these will give an {@link AbstractEntityVisual} access
 * to more interesting and regular points within a tick or a frame.
 *
 * @param <T> The type of {@link Entity}.
 */
public abstract class AbstractEntityVisual<T extends Entity> extends AbstractVisual implements EntityVisual<T> {
    protected final T entity;
    protected final EntityVisibilityTester visibilityTester;

    public AbstractEntityVisual(VisualizationContext ctx, T entity, float partialTick) {
        super(ctx, entity.world, partialTick);
        this.entity = entity;
        visibilityTester = new EntityVisibilityTester(entity, ctx.renderOrigin(), 1.5f);
    }

    /**
     * Calculate the distance squared between this visual and the given <em>level</em> position.
     *
     * @param x The x coordinate.
     * @param y The y coordinate.
     * @param z The z coordinate.
     * @return The distance squared between this visual and the given position.
     */
    public double distanceSquared(double x, double y, double z) {
        return entity.getDistanceSq(x, y, z);
    }

    /**
     * In order to accommodate for floating point precision errors at high coordinates,
     * {@link dev.engine_room.flywheel.api.visualization.VisualizationManager}s are allowed to arbitrarily adjust the origin, and
     * shift the level matrix provided as a shader uniform accordingly.
     *
     * @return The position this visual should be rendered at to appear in the correct location.
     */
    public Vector3f getVisualPosition() {
        Vec3d pos = entity.getPositionVector();
        var renderOrigin = renderOrigin();
        return new Vector3f((float) (pos.x - renderOrigin.getX()),
                (float) (pos.y - renderOrigin.getY()),
                (float) (pos.z - renderOrigin.getZ()));
    }

    /**
     * In order to accommodate for floating point precision errors at high coordinates,
     * {@link dev.engine_room.flywheel.api.visualization.VisualizationManager}s are allowed to arbitrarily adjust the origin, and
     * shift the level matrix provided as a shader uniform accordingly.
     *
     * @return The position this visual should be rendered at to appear in the correct location.
     */
    public Vector3f getVisualPosition(float partialTick) {
        var renderOrigin = renderOrigin();
        double px = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTick;
        double py = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTick;
        double pz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTick;
        return new Vector3f((float) (px - renderOrigin.getX()),
                (float) (py - renderOrigin.getY()),
                (float) (pz - renderOrigin.getZ()));
    }

    public boolean isVisible(FrustumIntersection frustum) {
        return entity.ignoreFrustumCheck || visibilityTester.check(frustum);
    }

    protected void translateToInterpolatedPosition(Matrix4f dest, float partialTick) {
        var origin = renderOrigin();
        double px = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTick;
        double py = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTick;
        double pz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTick;
        dest.translation((float) (px - origin.getX()), (float) (py - origin.getY()), (float) (pz - origin.getZ()));
    }

    protected int computePackedLight(float partialTick) {
        // 1.12 has no getLightProbePosition; in 1.21.1 it delegates to getEyePosition.
        return LevelRenderer.getEntityLight(entity, new BlockPos(entity.getPositionEyes(partialTick)));
    }

    protected void relight(float partialTick, @Nullable FlatLit... instances) {
        FlatLit.relight(computePackedLight(partialTick), instances);
    }
}
