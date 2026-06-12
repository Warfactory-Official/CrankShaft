package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.impl.test.OitDemoRegistration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public final class FlywheelFabricCommon implements ModInitializer {
    @Override
    public void onInitialize() {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            OitDemoRegistration.register();
        }
    }
}
