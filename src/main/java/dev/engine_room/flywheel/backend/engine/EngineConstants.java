package dev.engine_room.flywheel.backend.engine;

/**
 * Constants shared by the instancing and indirect backends. Both organize per-instance data into
 * fixed-size pages stored in {@link SlabBuffer}; the 32-instance page count lets the indirect path
 * pack page validity into a single 32-bit mask, and the instancing path inherits the same layout.
 */
public final class EngineConstants {
    public static final int LOG_2_PAGE_SIZE = 5;
    public static final int PAGE_SIZE = 1 << LOG_2_PAGE_SIZE;
    public static final int PAGE_MASK = PAGE_SIZE - 1;

    public static final int INITIAL_BUFFER_PAGES = 4;

    private EngineConstants() {
    }
}
