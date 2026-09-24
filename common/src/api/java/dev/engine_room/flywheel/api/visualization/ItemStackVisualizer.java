package dev.engine_room.flywheel.api.visualization;

import dev.engine_room.flywheel.api.visual.ItemStackVisual;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A visualizer keyed to an item: hosts draw its stacks through it instead of the item's renderer.
 * Registered with {@link VisualizerRegistry#setVisualizer(net.minecraft.world.item.Item, ItemStackVisualizer)}.
 * First-person and GUI rendering stay with the item's renderer.
 */
public interface ItemStackVisualizer {
    /**
     * Called by the host, on the host's threads.
     *
     * @param ctx   the host's context; instances belong to the host.
     * @param owner the holder, item entity, frame or display, when there is one.
     */
    ItemStackVisual createVisual(VisualizationContext ctx, ItemStack stack, ItemDisplayContext displayContext,
                                 @Nullable ItemOwner owner);
}
