package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.impl.FlwConfig;

public interface BackendConfig {
    BackendConfig INSTANCE = () -> FlwConfig.INSTANCE.lightSmoothness();

    /**
     * How smooth/accurate our flw_light impl is.
     *
     * @return The current light smoothness setting.
     */
    LightSmoothness lightSmoothness();
}
