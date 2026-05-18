package dev.engine_room.flywheel.backend.engine;

/**
 * CrankShaft shim: 1.12.2 has no {@code net.minecraft.core.SectionPos}. This class mirrors the static surface
 * that upstream {@code LightLut}/{@code LightStorage}/{@code LightDataCollector} use, with the same
 * long-packed format as Mojang 1.21: {@code (x & 0x3FFFFF) << 42 | (z & 0x3FFFFF) << 20 | (y & 0xFFFFF)}.
 * Sign-extends via arithmetic shifts.
 */
public final class SectionPos {
    private static final int PACKED_X_LENGTH = 22;
    private static final int PACKED_Z_LENGTH = 22;
    private static final int PACKED_Y_LENGTH = 64 - PACKED_X_LENGTH - PACKED_Z_LENGTH;
    private static final long PACKED_X_MASK = (1L << PACKED_X_LENGTH) - 1L;
    private static final long PACKED_Y_MASK = (1L << PACKED_Y_LENGTH) - 1L;
    private static final long PACKED_Z_MASK = (1L << PACKED_Z_LENGTH) - 1L;
    private static final int Y_OFFSET = 0;
    private static final int Z_OFFSET = PACKED_Y_LENGTH;
    private static final int X_OFFSET = Z_OFFSET + PACKED_Z_LENGTH;

    private SectionPos() {
    }

    public static long asLong(int x, int y, int z) {
        long l = 0L;
        l |= ((long) x & PACKED_X_MASK) << X_OFFSET;
        l |= ((long) y & PACKED_Y_MASK) << Y_OFFSET;
        l |= ((long) z & PACKED_Z_MASK) << Z_OFFSET;
        return l;
    }

    public static int x(long packed) {
        return (int) (packed << (64 - X_OFFSET - PACKED_X_LENGTH) >> (64 - PACKED_X_LENGTH));
    }

    public static int y(long packed) {
        return (int) (packed << (64 - Y_OFFSET - PACKED_Y_LENGTH) >> (64 - PACKED_Y_LENGTH));
    }

    public static int z(long packed) {
        return (int) (packed << (64 - Z_OFFSET - PACKED_Z_LENGTH) >> (64 - PACKED_Z_LENGTH));
    }

    public static long offset(long packed, int dx, int dy, int dz) {
        return asLong(x(packed) + dx, y(packed) + dy, z(packed) + dz);
    }

    public static int sectionToBlockCoord(int sectionCoord) {
        return sectionCoord << 4;
    }
}
