package dev.engine_room.flywheel.backend.lighting;

import dev.engine_room.flywheel.backend.lighting.TerrainGeometryCache.Capture;
import org.jspecify.annotations.Nullable;

public interface TerrainGeometryCarrier {
    @Nullable Capture lighting$terrainGeometry();

    void lighting$terrainGeometry(@Nullable Capture capture);
}
