package dev.engine_room.flywheel.impl;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.backend.compile.FlwProgramsReloader;
import dev.engine_room.flywheel.impl.event.RenderContextImpl;
import dev.engine_room.flywheel.impl.test.OitDemoRegistration;
import dev.engine_room.flywheel.impl.test.OitDemoVisual;
import dev.engine_room.flywheel.impl.visualization.VisualizationEventHandler;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.model.baked.PartialModelEventHandler;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@Mod(value = Flywheel.ID, dist = Dist.CLIENT)
public final class FlywheelNeoForge {
    public FlywheelNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        NeoForgeFlwConfig.INSTANCE.registerSpecs(modContainer);
        modEventBus.addListener((ModConfigEvent.Loading e) -> NeoForgeFlwConfig.INSTANCE.syncOitToRuntime());
        modEventBus.addListener((ModConfigEvent.Reloading e) -> NeoForgeFlwConfig.INSTANCE.syncOitToRuntime());
        BackendManagerImpl.init();
        InstanceTypes.TRANSFORMED.hashCode();
        Materials.SOLID_BLOCK.hashCode();
        FlwImpl.freezeRegistries();

        if (!FMLEnvironment.isProduction()) {
            OitDemoRegistration.register(modEventBus);
            modEventBus.addListener((FMLClientSetupEvent e) ->
                    SimpleBlockEntityVisualizer
                            .builder(OitDemoRegistration.OIT_DEMO_BE.get())
                            .factory(OitDemoVisual::new)
                            .apply());
        }

        IEventBus gameEventBus = NeoForge.EVENT_BUS;

        gameEventBus.addListener(FlwCommands::registerClientCommands);
        modEventBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() -> {
            ArgumentTypeInfos.registerByClass(BackendArgument.class, BackendArgument.INFO);
            ArgumentTypeInfos.registerByClass(DebugModeArgument.class, DebugModeArgument.INFO);
            ArgumentTypeInfos.registerByClass(LightSmoothnessArgument.class, LightSmoothnessArgument.INFO);
        }));
        modEventBus.addListener((RegisterDebugEntriesEvent e) -> {
            Identifier debugId = Identifier.fromNamespaceAndPath(Flywheel.ID, "debug_info");
            e.register(debugId, (displayer, entryLevel, clientChunk, serverChunk) -> {
                List<String> lines = new ArrayList<>();
                FlwDebugInfo.addDebugInfo(Minecraft.getInstance(), lines);
                displayer.addToGroup(debugId, lines);
            });
            e.includeInProfile(debugId, DebugScreenProfile.DEFAULT, DebugScreenEntryStatus.IN_OVERLAY);
            DebugScreenEntryList debugEntries = Minecraft.getInstance().debugEntries;
            if (debugEntries != null && debugEntries.isUsingProfile(DebugScreenProfile.DEFAULT)) {
                debugEntries.loadProfile(DebugScreenProfile.DEFAULT);
            }
        });

        modEventBus.addListener(PartialModelEventHandler::onRegisterStandalone);
        modEventBus.addListener(PartialModelEventHandler::onBakingCompleted);

        modEventBus.addListener((AddClientReloadListenersEvent e) ->
                e.addListener(FlwProgramsReloader.ID, FlwProgramsReloader.INSTANCE));

        gameEventBus.addListener((LevelTickEvent.Post e) -> {
            // Make sure we don't tick on the server somehow.
            if (e.getLevel().isClientSide()) {
                VisualizationEventHandler.onClientTick(Minecraft.getInstance(), e.getLevel());
            }
        });
        gameEventBus.addListener((EntityJoinLevelEvent e) ->
                VisualizationEventHandler.onEntityJoinLevel(e.getLevel(), e.getEntity()));
        gameEventBus.addListener((EntityLeaveLevelEvent e) ->
                VisualizationEventHandler.onEntityLeaveLevel(e.getLevel(), e.getEntity()));
        gameEventBus.addListener((LevelEvent.Unload e) -> {
            if (e.getLevel() instanceof Level level && level.isClientSide()) {
                VisualizationEventHandler.onLevelUnload(level);
            }
        });

        gameEventBus.addListener((RenderLevelStageEvent.AfterOpaqueFeatures e) -> {
            Minecraft mc = Minecraft.getInstance();
            VisualizationManagerImpl manager = VisualizationManagerImpl.get(mc.level);
            if (manager == null) {
                return;
            }
            RenderContextImpl ctx = buildContext(e.getLevelRenderer(), e.getModelViewMatrix());
            if (ctx == null) {
                return;
            }
            manager.renderDispatcher().afterEntities(ctx);
            manager.renderDispatcher().beforeCrumbling(ctx, mc.level.destructionProgress());
        });
    }

    @Nullable
    private static RenderContextImpl buildContext(LevelRenderer renderer, Matrix4fc modelViewMatrix) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel level)) {
            return null;
        }
        return RenderContextImpl.captureCurrent(renderer, level, modelViewMatrix);
    }
}
