package dev.engine_room.flywheel.impl.far;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Owned sparse placement arrays transferred from a terrain reader to the render thread.
 */
public record FarTerrainPage(int x, int z, long[] positions, BlockState[] states, int[] lights) {
}
