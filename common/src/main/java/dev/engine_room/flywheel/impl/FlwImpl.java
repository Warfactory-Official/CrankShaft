package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.impl.registry.IdRegistryImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FlwImpl {
    public static final Logger LOGGER = LogManager.getLogger(Flywheel.ID);
    public static final Logger CONFIG_LOGGER = LogManager.getLogger(Flywheel.ID + "/config");

    private FlwImpl() {
    }

    public static void freezeRegistries() {
        IdRegistryImpl.freezeAll();
    }
}
