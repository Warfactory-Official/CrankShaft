package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;

/** A block entity visual with cached draw bounds independent of its light extent. */
public abstract class AbstractBoundedBlockEntityVisual<T extends BlockEntity> extends AbstractBlockEntityVisual<T> {
    protected float visibleMinX, visibleMinY, visibleMinZ;
    protected float visibleMaxX, visibleMaxY, visibleMaxZ;
    private boolean boundsCached;

    protected AbstractBoundedBlockEntityVisual(VisualizationContext context, T blockEntity, float partialTick) {
        super(context, blockEntity, partialTick);
    }

    protected AABB visibleBounds() {
        return getRenderBoundingBox();
    }

    protected final void invalidateVisibleBounds() {
        boundsCached = false;
    }

    protected final boolean hasVisibleBounds() {
        return boundsCached;
    }

    protected boolean testCurrentBounds(FrustumIntersection frustum) {
        if (!boundsCached) {
            AABB bounds = visibleBounds();
            var origin = renderOrigin();
            visibleMinX = (float) (bounds.minX - origin.getX());
            visibleMinY = (float) (bounds.minY - origin.getY());
            visibleMinZ = (float) (bounds.minZ - origin.getZ());
            visibleMaxX = (float) (bounds.maxX - origin.getX());
            visibleMaxY = (float) (bounds.maxY - origin.getY());
            visibleMaxZ = (float) (bounds.maxZ - origin.getZ());
            boundsCached = true;
        }
        return frustum.testAab(visibleMinX, visibleMinY, visibleMinZ, visibleMaxX, visibleMaxY, visibleMaxZ);
    }

    @Override
    public boolean isVisible(FrustumIntersection frustum) {
        return testCurrentBounds(frustum);
    }
}
