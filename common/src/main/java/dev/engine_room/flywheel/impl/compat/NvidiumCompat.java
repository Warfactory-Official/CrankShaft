package dev.engine_room.flywheel.impl.compat;

import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDispatchers;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Nvidium stands down while the engine renders terrain: both replace Sodium's terrain upload and draws. Its options
 * are greyed out and explain why.
 */
public final class NvidiumCompat {
    private NvidiumCompat() {
    }

    public static boolean engineOwnsTerrain() {
        return BackendManagerImpl.isBackendOn() && BackendConfig.INSTANCE.terrainMode() != TerrainMode.OFF
                && TerrainDispatchers.isSupported();
    }

    public static boolean isNvidiumOption(Identifier id) {
        return id.getNamespace().equals("nvidium");
    }

    public static Component terrainOwnedTooltip() {
        return Component.translatable("flywheel.compat.nvidium.terrain_owned",
                BackendConfig.INSTANCE.terrainMode().token());
    }
}
