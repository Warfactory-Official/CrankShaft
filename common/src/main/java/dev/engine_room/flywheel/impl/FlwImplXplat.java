package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.internal.DependencyInjection;
import net.minecraft.client.multiplayer.ClientLevel;

public interface FlwImplXplat {
    FlwImplXplat INSTANCE = DependencyInjection.load(FlwImplXplat.class,
            "dev.engine_room.flywheel.impl.FlwImplXplatImpl");

    boolean isModLoaded(String modId);

    void dispatchReloadLevelRendererEvent(ClientLevel level);

    FlwConfig getConfig();

    boolean vanillaOwnsClouds();

    boolean vanillaOwnsWeather();
}
