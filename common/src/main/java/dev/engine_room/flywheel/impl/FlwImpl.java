package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.impl.compat.LambDynLightsCompat;
import dev.engine_room.flywheel.impl.compat.VoxyCompat;
import dev.engine_room.flywheel.impl.registry.IdRegistryImpl;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.Materials;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FlwImpl {
    public static final Logger LOGGER = LogManager.getLogger(Flywheel.ID);
    public static final Logger CONFIG_LOGGER = LogManager.getLogger(Flywheel.ID + "/config");

    private FlwImpl() {
    }

    public static void init() {
        BackendManagerImpl.init();
        LambDynLightsCompat.init();
        DistantHorizonsCompat.init();
        VoxyCompat.init();
        InstanceTypes.TRANSFORMED.hashCode();
        Materials.SOLID_BLOCK.hashCode();
        freezeRegistries();
    }

    public static void freezeRegistries() {
        IdRegistryImpl.freezeAll();
    }
}
