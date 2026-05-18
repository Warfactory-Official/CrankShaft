package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.api.Flywheel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FlwBackend {
    public static final Logger LOGGER = LogManager.getLogger(Flywheel.ID + "/backend");

    private FlwBackend() {
    }
}
