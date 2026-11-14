package dev.engine_room.flywheel.api.visualization;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Creates a distant representation without a live block entity or visualization context.
 * Providers run on the render thread after renderer resources are available. All transforms
 * in the returned model are relative to the block's origin.
 */
@FunctionalInterface
public interface FarVisualProvider {
    FarVisualModel create(BlockState state);
}
