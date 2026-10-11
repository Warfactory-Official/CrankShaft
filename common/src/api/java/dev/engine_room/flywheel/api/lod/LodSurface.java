package dev.engine_room.flywheel.api.lod;

import org.jspecify.annotations.Nullable;

/**
 * LoD terrain heights over the {@code size}x{@code size} block square whose minimum corner is ({@code minX},
 * {@code minZ}): per column, what {@code Level.getHeight(type, x, z)} answers over loaded terrain for the
 * {@link LodTerrain#surface} request's {@code type}, read from the blocks the LoD mod holds. {@code tops} is row-major
 * in Z, {@code null} when no LoD mod holds the area.
 */
public record LodSurface(int minX, int minZ, int size, int @Nullable [] tops) {
    public static final int UNKNOWN = Integer.MIN_VALUE;

    /**
     * {@link #UNKNOWN} outside the square or where the LoD mod holds no data.
     */
    public int top(int x, int z) {
        int dx = x - minX, dz = z - minZ;
        if (tops == null || dx < 0 || dz < 0 || dx >= size || dz >= size) {
            return UNKNOWN;
        }
        return tops[dz * size + dx];
    }
}
