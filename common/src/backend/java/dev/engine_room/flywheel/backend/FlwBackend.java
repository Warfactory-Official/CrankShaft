package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.api.Flywheel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

public final class FlwBackend {
    public static final Logger LOGGER = LogManager.getLogger(Flywheel.ID + "/backend");

    private static @Nullable BackendConfig config;

    private FlwBackend() {
    }

    public static BackendConfig config() {
        return config;
    }

    public static void init(BackendConfig config) {
        FlwBackend.config = config;
        Backends.init();
    }
}
