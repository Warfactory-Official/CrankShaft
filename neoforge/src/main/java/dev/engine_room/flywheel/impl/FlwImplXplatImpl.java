package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.api.event.ReloadLevelRendererEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;

public class FlwImplXplatImpl implements FlwImplXplat {
	@Override
	public boolean isModLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}

	@Override
	public void dispatchReloadLevelRendererEvent(ClientLevel level) {
		NeoForge.EVENT_BUS.post(new ReloadLevelRendererEvent(level));
	}

	@Override
	public FlwConfig getConfig() {
		return NeoForgeFlwConfig.INSTANCE;
	}

	@Override
	public boolean vanillaOwnsClouds() {
		return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.customCloudsRenderer == null;
	}

	@Override
	public boolean vanillaOwnsWeather() {
		return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.customWeatherEffectRenderer == null;
	}

}
