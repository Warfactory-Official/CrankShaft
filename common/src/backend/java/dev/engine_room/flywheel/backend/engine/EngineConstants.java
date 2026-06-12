package dev.engine_room.flywheel.backend.engine;

public final class EngineConstants {
    // 32 objects per page. Allows for convenient bitsets on the gpu.
    public static final int LOG_2_PAGE_SIZE = 5;
    public static final int PAGE_SIZE = 1 << LOG_2_PAGE_SIZE;
    public static final int PAGE_MASK = PAGE_SIZE - 1;

    public static final int INITIAL_BUFFER_PAGES = 4;

    private EngineConstants() {
    }
}
