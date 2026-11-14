package dev.engine_room.flywheel.lib.visual;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.FrustumIntersection;

/** Retains the previous draw extent until changed geometry has been applied. */
public abstract class AbstractDynamicExtentBlockEntityVisual<T extends BlockEntity> extends AbstractBoundedBlockEntityVisual<T> {
    private float previousMinX, previousMinY, previousMinZ;
    private float previousMaxX, previousMaxY, previousMaxZ;
    private boolean previousBoundsPending;
    private boolean previousBoundsKnown;

    protected AbstractDynamicExtentBlockEntityVisual(VisualizationContext context, T blockEntity, float partialTick) {
        super(context, blockEntity, partialTick);
    }

    protected final void refreshVisibleBounds() {
        if (!previousBoundsPending) {
            previousBoundsKnown = hasVisibleBounds();
            if (previousBoundsKnown) {
                previousMinX = visibleMinX;
                previousMinY = visibleMinY;
                previousMinZ = visibleMinZ;
                previousMaxX = visibleMaxX;
                previousMaxY = visibleMaxY;
                previousMaxZ = visibleMaxZ;
            }
            previousBoundsPending = true;
        }
        invalidateVisibleBounds();
    }

    protected final void visibleBoundsApplied() {
        previousBoundsPending = false;
    }

    @Override
    public boolean isVisible(FrustumIntersection frustum) {
        return testCurrentBounds(frustum)
                || previousBoundsPending && (!previousBoundsKnown || frustum.testAab(
                        previousMinX, previousMinY, previousMinZ, previousMaxX, previousMaxY, previousMaxZ));
    }

    public void afterFrame() {
        visibleBoundsApplied();
    }
}
