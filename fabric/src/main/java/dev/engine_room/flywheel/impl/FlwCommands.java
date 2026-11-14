package dev.engine_room.flywheel.impl;

import com.mojang.brigadier.Command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.GpuTimer;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDebug;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class FlwCommands {
    private FlwCommands() {
    }

    public static void registerClientCommands(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                              CommandBuildContext _) {
        LiteralArgumentBuilder<FabricClientCommandSource> command = ClientCommands.literal("flywheel");

        command.then(ClientCommands.literal("backend")
                                   .executes(context -> {
                                       Backend backend = BackendManager.currentBackend();
                                       String idStr = Backend.REGISTRY.getIdOrThrow(backend)
                                                                      .toString();
                                       context.getSource()
                                              .sendFeedback(
                                                      Component.translatable("command.flywheel.backend.get", idStr));
                                       return Command.SINGLE_SUCCESS;
                                   })
                                   .then(ClientCommands.literal("DEFAULT")
                                                       .executes(context -> {
                                                           FabricFlwConfig.INSTANCE.setBackendString(
                                                                   FlwConfig.DEFAULT_BACKEND_STR);

                                                           // Reload renderers so we can report the actual backend.
                                                           reloadRenderers();

                                                           Backend actualBackend = BackendManager.currentBackend();
                                                           String actualIdStr = Backend.REGISTRY.getIdOrThrow(
                                                                                               actualBackend)
                                                                                                .toString();
                                                           context.getSource()
                                                                  .sendFeedback(Component.translatable(
                                                                          "command.flywheel.backend.set", actualIdStr));
                                                           return Command.SINGLE_SUCCESS;
                                                       }))
                                   .then(ClientCommands.argument("id", BackendArgument.INSTANCE)
                                                       .executes(context -> {
                                                           Backend requestedBackend = context.getArgument("id",
                                                                   Backend.class);
                                                           String requestedIdStr = Backend.REGISTRY.getIdOrThrow(
                                                                                                  requestedBackend)
                                                                                                   .toString();
                                                           FabricFlwConfig.INSTANCE.setBackendString(requestedIdStr);

                                                           // Reload renderers so we can report the actual backend.
                                                           reloadRenderers();

                                                           Backend actualBackend = BackendManager.currentBackend();
                                                           if (actualBackend != requestedBackend) {
                                                               context.getSource()
                                                                      .sendError(Component.translatable(
                                                                              "command.flywheel.backend.set.unavailable",
                                                                              requestedIdStr));
                                                           }

                                                           String actualIdStr = Backend.REGISTRY.getIdOrThrow(
                                                                                               actualBackend)
                                                                                                .toString();
                                                           context.getSource()
                                                                  .sendFeedback(Component.translatable(
                                                                          "command.flywheel.backend.set", actualIdStr));
                                                           return Command.SINGLE_SUCCESS;
                                                       })));

        command.then(ClientCommands.literal("limitUpdates")
                                   .executes(context -> {
                                       if (FabricFlwConfig.INSTANCE.limitUpdates()) {
                                           context.getSource()
                                                  .sendFeedback(Component.translatable(
                                                          "command.flywheel.limit_updates.get.on"));
                                       } else {
                                           context.getSource()
                                                  .sendFeedback(Component.translatable(
                                                          "command.flywheel.limit_updates.get.off"));
                                       }
                                       return Command.SINGLE_SUCCESS;
                                   })
                                   .then(ClientCommands.literal("on")
                                                       .executes(context -> {
                                                           FabricFlwConfig.INSTANCE.setLimitUpdates(true);
                                                           context.getSource()
                                                                  .sendFeedback(Component.translatable(
                                                                          "command.flywheel.limit_updates.set.on"));
                                                           reloadRenderers();
                                                           return Command.SINGLE_SUCCESS;
                                                       }))
                                   .then(ClientCommands.literal("off")
                                                       .executes(context -> {
                                                           FabricFlwConfig.INSTANCE.setLimitUpdates(false);
                                                           context.getSource()
                                                                  .sendFeedback(Component.translatable(
                                                                          "command.flywheel.limit_updates.set.off"));
                                                           reloadRenderers();
                                                           return Command.SINGLE_SUCCESS;
                                                       })));

        command.then(ClientCommands.literal("lightSmoothness")
                                   .then(ClientCommands.argument("mode", LightSmoothnessArgument.INSTANCE)
                                                       .executes(context -> {
                                                           LightSmoothness oldValue = FabricFlwConfig.INSTANCE.backendConfig.lightSmoothness;
                                                           LightSmoothness newValue = context.getArgument("mode",
                                                                   LightSmoothness.class);

                                                           if (oldValue != newValue) {
                                                               FabricFlwConfig.INSTANCE.setLightSmoothness(newValue);
                                                           }
                                                           return Command.SINGLE_SUCCESS;
                                                       })));

        command.then(ClientCommands.literal("terrain")
                                   .then(ClientCommands.literal("off")
                                                       .executes(context -> {
                                                           FabricFlwConfig.INSTANCE.setTerrainMode(TerrainMode.OFF);
                                                           reloadRenderers();
                                                           context.getSource()
                                                                  .sendFeedback(
                                                                          FlwCommandText.terrainSet(TerrainMode.OFF));
                                                           return Command.SINGLE_SUCCESS;
                                                       }))
                                   .then(ClientCommands.literal("translucent")
                                                       .executes(context -> {
                                                           FabricFlwConfig.INSTANCE.setTerrainMode(
                                                                   TerrainMode.TRANSLUCENT_OIT);
                                                           reloadRenderers();
                                                           context.getSource().sendFeedback(
                                                                   FlwCommandText.terrainSet(
                                                                           TerrainMode.TRANSLUCENT_OIT));
                                                           return Command.SINGLE_SUCCESS;
                                                       }))
                                   .then(ClientCommands.literal("opaque")
                                                       .executes(context -> {
                                                           if (!SodiumCompat.ACTIVE) {
                                                               context.getSource().sendError(
                                                                       FlwCommandText.terrainRequiresSodium(
                                                                               TerrainMode.OPAQUE));
                                                               return 0;
                                                           }
                                                           FabricFlwConfig.INSTANCE.setTerrainMode(TerrainMode.OPAQUE);
                                                           reloadRenderers();
                                                           context.getSource().sendFeedback(
                                                                   FlwCommandText.terrainSet(TerrainMode.OPAQUE));
                                                           return Command.SINGLE_SUCCESS;
                                                       }))
                                   .then(ClientCommands.literal("full")
                                                       .executes(context -> {
                                                           if (!SodiumCompat.ACTIVE) {
                                                               context.getSource().sendError(
                                                                       FlwCommandText.terrainRequiresSodium(
                                                                               TerrainMode.FULL));
                                                               return 0;
                                                           }
                                                           FabricFlwConfig.INSTANCE.setTerrainMode(TerrainMode.FULL);
                                                           reloadRenderers();
                                                           context.getSource()
                                                                  .sendFeedback(
                                                                          FlwCommandText.terrainSet(TerrainMode.FULL));
                                                           return Command.SINGLE_SUCCESS;
                                                       })));

        command.then(createOitCommand());

        command.then(createDebugCommand());

        dispatcher.register(command);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> createOitCommand() {
        var oit = ClientCommands.literal("oit")
                                .executes(context -> {
                                    context.getSource().sendFeedback(OitConfig.statusComponent());
                                    return Command.SINGLE_SUCCESS;
                                });
        var modeCmd = ClientCommands.literal("mode");
        for (OitConfig.Path p : OitConfig.Path.values()) {
            modeCmd.then(ClientCommands.literal(p.getSerializedName())
                                       .executes(context -> {
                                           OitConfig.setPath(p);
                                           context.getSource().sendFeedback(OitConfig.statusComponent());
                                           return Command.SINGLE_SUCCESS;
                                       }));
        }
        oit.then(modeCmd);
        oit.then(ClientCommands.literal("layers")
                               .then(ClientCommands.argument("n", IntegerArgumentType.integer(0, OitConfig.MAX_LAYERS))
                                                   .executes(context -> {
                                                       int n = IntegerArgumentType.getInteger(context, "n");
                                                       var mode = OitConfig.setLayersForEffective(n);
                                                       if (mode == null) {
                                                           context.getSource().sendError(Component.translatable(
                                                                   "command.flywheel.oit.no_insert"));
                                                           return 0;
                                                       }
                                                       context.getSource()
                                                              .sendFeedback(OitConfig.statusComponent());
                                                       return Command.SINGLE_SUCCESS;
                                                   })));
        oit.then(ClientCommands.literal("reset")
                               .executes(context -> {
                                   OitConfig.resetLayers();
                                   context.getSource().sendFeedback(OitConfig.statusComponent());
                                   return Command.SINGLE_SUCCESS;
                               }));
        #if MC < 26.3
        oit.then(ClientCommands.literal("exactweather")
                               .then(ClientCommands.argument("value", BoolArgumentType.bool())
                                                   .executes(context -> {
                                                       OitConfig.setExactFabulous(
                                                               BoolArgumentType.getBool(context, "value"));
                                                       context.getSource()
                                                              .sendFeedback(OitConfig.statusComponent());
                                                       return Command.SINGLE_SUCCESS;
                                                   })));
        #endif
        return oit;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> createDebugCommand() {
        var debug = ClientCommands.literal("debug");

        debug.then(ClientCommands.literal("crumbling")
                                 .then(ClientCommands.argument("pos", BlockPosArgument.blockPos())
                                                     .then(ClientCommands.argument("stage",
                                                                                 IntegerArgumentType.integer(0, 9))
                                                                         .executes(context -> {
                                                                             Entity executor = context.getSource()
                                                                                                      .getEntity();

                                                                             if (executor == null) {
                                                                                 return 0;
                                                                             }

                                                                             BlockPos pos = getBlockPos(context, "pos");
                                                                             int value = IntegerArgumentType.getInteger(
                                                                                     context, "stage");

                                                                             executor.level()
                                                                                     .destroyBlockProgress(
                                                                                             executor.getId(), pos,
                                                                                             value);

                                                                             return Command.SINGLE_SUCCESS;
                                                                         }))));

        debug.then(ClientCommands.literal("shader")
                                 .then(ClientCommands.argument("mode", DebugModeArgument.INSTANCE)
                                                     .executes(context -> {
                                                         DebugMode mode = context.getArgument("mode", DebugMode.class);
                                                         FrameUniforms.debugMode(mode);
                                                         return Command.SINGLE_SUCCESS;
                                                     })));

        debug.then(ClientCommands.literal("frustum")
                                 .then(ClientCommands.literal("capture")
                                                     .executes(_ -> {
                                                         FrameUniforms.captureFrustum();
                                                         return Command.SINGLE_SUCCESS;
                                                     }))
                                 .then(ClientCommands.literal("unpause")
                                                     .executes(_ -> {
                                                         FrameUniforms.unpauseFrustum();
                                                         return Command.SINGLE_SUCCESS;
                                                     })));

        debug.then(ClientCommands.literal("lightSections")
                                 .then(ClientCommands.literal("on")
                                                     .executes(_ -> {
                                                         BackendDebugFlags.LIGHT_STORAGE_VIEW = true;
                                                         return Command.SINGLE_SUCCESS;
                                                     }))
                                 .then(ClientCommands.literal("off")
                                                     .executes(_ -> {
                                                         BackendDebugFlags.LIGHT_STORAGE_VIEW = false;
                                                         return Command.SINGLE_SUCCESS;
                                                     })));

        debug.then(ClientCommands.literal("oit")
                                 .then(ClientCommands.literal("on")
                                                     .executes(_ -> {
                                                         BackendDebugFlags.SKIP_OIT = false;
                                                         return Command.SINGLE_SUCCESS;
                                                     }))
                                 .then(ClientCommands.literal("off")
                                                     .executes(_ -> {
                                                         BackendDebugFlags.SKIP_OIT = true;
                                                         return Command.SINGLE_SUCCESS;
                                                     })));

        #if MC < 26.3
        debug.then(ClientCommands.literal("fabulousLayers")
                                 .then(ClientCommands.literal("on")
                                                     .executes(context -> {
                                                         ImplDebugFlags.FABULOUS_LAYER_VIEW = true;
                                                         context.getSource().sendFeedback(Component.translatable(
                                                                 "command.flywheel.debug.fabulous_layers.on"));
                                                         return Command.SINGLE_SUCCESS;
                                                     }))
                                 .then(ClientCommands.literal("off")
                                                     .executes(context -> {
                                                         ImplDebugFlags.FABULOUS_LAYER_VIEW = false;
                                                         context.getSource().sendFeedback(Component.translatable(
                                                                 "command.flywheel.debug.fabulous_layers.off"));
                                                         return Command.SINGLE_SUCCESS;
                                                     })));
        #endif

        debug.then(ClientCommands.literal("sodiumCull")
                                 .then(ClientCommands.literal("on")
                                                     .executes(context -> {
                                                         TerrainDebug.SODIUM_CULL = true;
                                                         context.getSource().sendFeedback(Component.translatable(
                                                                 "command.flywheel.debug.sodium_cull.on"));
                                                         return Command.SINGLE_SUCCESS;
                                                     }))
                                 .then(ClientCommands.literal("off")
                                                     .executes(context -> {
                                                         TerrainDebug.SODIUM_CULL = false;
                                                         context.getSource().sendFeedback(Component.translatable(
                                                                 "command.flywheel.debug.sodium_cull.off"));
                                                         return Command.SINGLE_SUCCESS;
                                                     })));

        debug.then(ClientCommands.literal("pauseUpdates")
                                 .then(ClientCommands.literal("on")
                                                     .executes(_ -> {
                                                         ImplDebugFlags.PAUSE_UPDATES = true;
                                                         return Command.SINGLE_SUCCESS;
                                                     }))
                                 .then(ClientCommands.literal("off")
                                                     .executes(_ -> {
                                                         ImplDebugFlags.PAUSE_UPDATES = false;
                                                         return Command.SINGLE_SUCCESS;
                                                     })));

        debug.then(ClientCommands.literal("terrainHiZ")
                                 .then(ClientCommands.literal("on")
                                                     .executes(context -> {
                                                         TerrainDebug.HIZ_ENABLED = true;
                                                         FlwCommandText.glOnlyFeedback(
                                                                 context.getSource()::sendFeedback);
                                                         return Command.SINGLE_SUCCESS;
                                                     }))
                                 .then(ClientCommands.literal("off")
                                                     .executes(context -> {
                                                         TerrainDebug.HIZ_ENABLED = false;
                                                         FlwCommandText.glOnlyFeedback(
                                                                 context.getSource()::sendFeedback);
                                                         return Command.SINGLE_SUCCESS;
                                                     })));

        debug.then(ClientCommands.literal("gpuTimer")
                                 .then(ClientCommands.literal("on")
                                                     .executes(context -> {
                                                         FlwCommandText.gpuTimerState(GpuTimer.setEnabled(true),
                                                                 context.getSource()::sendFeedback);
                                                         return Command.SINGLE_SUCCESS;
                                                     }))
                                 .then(ClientCommands.literal("off")
                                                     .executes(context -> {
                                                         FlwCommandText.gpuTimerState(GpuTimer.setEnabled(false),
                                                                 context.getSource()::sendFeedback);
                                                         return Command.SINGLE_SUCCESS;
                                                     }))
                                 .then(ClientCommands.literal("once")
                                                     .executes(context -> {
                                                         FlwCommandText.gpuTimerState(GpuTimer.captureOnce(),
                                                                 context.getSource()::sendFeedback);
                                                         return Command.SINGLE_SUCCESS;
                                                     }))
                                 .then(ClientCommands.literal("summary")
                                                     .executes(context -> {
                                                         context.getSource().sendFeedback(
                                                                 GpuTimer.commandReport());
                                                         return Command.SINGLE_SUCCESS;
                                                     })));

        debug.then(ClientCommands.literal("info")
                                 .executes(context -> {
                                     context.getSource()
                                            .sendFeedback(FlwDebugInfo.getDebugCommandInfo());
                                     return Command.SINGLE_SUCCESS;
                                 }));

        return debug;
    }


    private static void reloadRenderers() {
        Minecraft mc = Minecraft.getInstance();
        mc.levelExtractor.allChanged();
        if (mc.level != null) {
            BackendManagerImpl.onReloadLevelRenderer(mc.level);
        }
    }

    private static BlockPos getBlockPos(CommandContext<FabricClientCommandSource> context, String name) {
        WorldCoordinates coords = context.getArgument(name, WorldCoordinates.class);
        Vec3 base = context.getSource()
                           .getPosition();
        return BlockPos.containing(coords.x()
                                         .get(base.x), coords.y()
                                                             .get(base.y), coords.z()
                                                                                 .get(base.z));
    }
}
