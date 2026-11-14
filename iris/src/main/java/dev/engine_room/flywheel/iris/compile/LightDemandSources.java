package dev.engine_room.flywheel.iris.compile;

/** Dimension-owned geometry preparation and exact refraction-query footprint. */
public record LightDemandSources(String prepare, String query) {
    public static final int SIMPLE_CLASSIFIED = 16;
    public static final int SIMPLE_INITIALIZED = 0x80000000;
}
