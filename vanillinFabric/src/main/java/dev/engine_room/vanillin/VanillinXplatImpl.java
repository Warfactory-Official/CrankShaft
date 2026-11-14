package dev.engine_room.vanillin;

import dev.engine_room.vanillin.config.VanillinConfig;
import net.fabricmc.loader.api.FabricLoader;

public class VanillinXplatImpl implements VanillinXplat {
    @Override
    public VanillinConfig getConfig() {
        return FabricVanillinConfig.INSTANCE;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance()
                           .isDevelopmentEnvironment();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance()
                           .isModLoaded(modId);
    }
}
