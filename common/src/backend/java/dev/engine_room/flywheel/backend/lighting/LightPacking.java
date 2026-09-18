package dev.engine_room.flywheel.backend.lighting;

/**
 * Byte layout shared by the CPU collector and the fragment light sampler on both loaders.
 */
public final class LightPacking {
    public static final int BLOCKS_PER_SECTION = 18 * 18 * 18;
    public static final int LIGHT_SIZE_BYTES = BLOCKS_PER_SECTION;
    public static final int SOLID_SIZE_BYTES = (BLOCKS_PER_SECTION + 31) / 32 * Integer.BYTES;
    public static final int SECTION_SIZE_BYTES = SOLID_SIZE_BYTES + LIGHT_SIZE_BYTES;

    private LightPacking() {
    }
}
