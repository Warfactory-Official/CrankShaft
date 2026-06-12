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

    /**
     * Whether the mesh-shader terrain tiers ({@code gl_mesh_shader} / {@code vk_mesh_shader}) copy Sodium's live
     * geometry arena into a mod-owned device-local buffer (true) instead of aliasing it in place (false, the
     * zero-repack default). Read when a mesh {@code MeshEngine} is constructed; a runtime change applies on the
     * next renderer reload (driven by {@code /flywheel ownGeometry}).
     */
    boolean ownGeometry();
}
