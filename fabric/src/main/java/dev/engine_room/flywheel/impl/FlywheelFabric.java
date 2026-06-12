package dev.engine_room.flywheel.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.backend.compile.FlwProgramsReloader;
import dev.engine_room.flywheel.impl.test.OitDemoContent;
import dev.engine_room.flywheel.impl.test.OitDemoVisual;
import dev.engine_room.flywheel.impl.visualization.VisualizationEventHandler;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.model.baked.PartialModelEventHandler;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.Level;

public final class FlywheelFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BackendManagerImpl.init();
		InstanceTypes.TRANSFORMED.hashCode();
		Materials.SOLID_BLOCK.hashCode();
		FlwImpl.freezeRegistries();
		FabricFlwConfig.INSTANCE.load();

		registerReloadListener();
		registerPartialModels();
		registerLifecycleEvents();
		registerDebugFeatures();
		registerF3DebugEntry();
	}

	private static void registerF3DebugEntry() {
		Identifier debugId = Identifier.fromNamespaceAndPath(Flywheel.ID, "debug_info");
		DebugScreenEntries.register(debugId, (displayer, serverOrClientLevel, clientChunk, serverChunk) -> {
			List<String> lines = new ArrayList<>();
			FlwDebugInfo.addDebugInfo(Minecraft.getInstance(), lines);
			displayer.addToGroup(debugId, lines);
		});
		Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> rebuilt = new HashMap<>();
		DebugScreenEntries.PROFILES.forEach((profile, statuses) -> rebuilt.put(profile, new HashMap<>(statuses)));
		rebuilt.computeIfAbsent(DebugScreenProfile.DEFAULT, profile -> new HashMap<>())
				.put(debugId, DebugScreenEntryStatus.IN_OVERLAY);
		DebugScreenEntries.PROFILES = rebuilt;
	}

	private static void registerDebugFeatures() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				FlwCommands.registerClientCommands(dispatcher, buildContext));

		ArgumentTypeInfos.BY_CLASS.put(BackendArgument.class, BackendArgument.INFO);
		ArgumentTypeInfos.BY_CLASS.put(DebugModeArgument.class, DebugModeArgument.INFO);
		ArgumentTypeInfos.BY_CLASS.put(LightSmoothnessArgument.class, LightSmoothnessArgument.INFO);

		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			SimpleBlockEntityVisualizer.builder(OitDemoContent.OIT_DEMO_BE)
					.factory(OitDemoVisual::new)
					.apply();
		}
	}

	private static void registerReloadListener() {
		ResourceLoader.get(PackType.CLIENT_RESOURCES)
				.registerReloadListener(FlwProgramsReloader.ID, FlwProgramsReloader.INSTANCE);
	}

	private static void registerPartialModels() {
		ModelLoadingPlugin.register(PartialModelEventHandler::onDefineModels);
		ResourceLoader loader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
		loader.registerReloadListener(PartialModelEventHandler.ReloadListener.ID, PartialModelEventHandler.ReloadListener.INSTANCE);
		loader.addListenerOrdering(ResourceReloaderKeys.Client.MODELS, PartialModelEventHandler.ReloadListener.ID);
	}

	private static void registerLifecycleEvents() {
		// This Fabric event runs slightly later than the Forge event Flywheel uses, but it shouldn't make a difference.
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			if (minecraft.isPaused()) {
				return;
			}
			Level level = minecraft.level;
			if (level != null) {
				VisualizationEventHandler.onClientTick(minecraft, level);
			}
		});
		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> VisualizationEventHandler.onEntityJoinLevel(level, entity));
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> VisualizationEventHandler.onEntityLeaveLevel(level, entity));
	}
}
