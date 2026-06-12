package dev.engine_room.flywheel.backend.engine.terrain;

public interface OwnedGeometryListener {
    void onRegionDirty(int regionId);

    void onRegionFreed(int regionId);
}
