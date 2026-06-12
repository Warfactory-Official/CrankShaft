package dev.engine_room.vanillin;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

public class VanillinXplatImpl implements VanillinXplat {
    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.isProduction();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }
}
