package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.backend.compile.LightSmoothness;

public interface BackendConfig {
    BackendConfig INSTANCE = FlwBackend.config();

    /**
     * How smooth/accurate our flw_light impl is.
     *
     * @return The current light smoothness setting.
     */
    LightSmoothness lightSmoothness();

    /**
     * How much chunk terrain flywheel takes over. See {@link TerrainMode#ownsOpaque()} /
     * {@link TerrainMode#compositesTranslucent()} for the per-layer predicates.
     */
    TerrainMode terrainMode();
}
