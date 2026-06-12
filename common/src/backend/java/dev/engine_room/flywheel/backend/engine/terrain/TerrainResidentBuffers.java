package dev.engine_room.flywheel.backend.engine.terrain;

public interface TerrainResidentBuffers {
    TerrainResidentBuffer createMirror(long stride);

    TerrainResidentBuffer createDynamic();

    void flushPendingWrites();

    void flushBeforeGrow();
}
