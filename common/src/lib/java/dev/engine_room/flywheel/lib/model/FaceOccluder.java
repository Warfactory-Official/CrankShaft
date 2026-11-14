package dev.engine_room.flywheel.lib.model;

import net.minecraft.core.Direction;

@FunctionalInterface
public interface FaceOccluder {
    /**
     * The neighbour's face opposite {@code facing} fully conceals this cell face. Unknown coverage is false.
     */
    boolean occludes(int x, int y, int z, Direction facing);
}
