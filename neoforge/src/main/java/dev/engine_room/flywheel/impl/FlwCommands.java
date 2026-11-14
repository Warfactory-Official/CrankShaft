package dev.engine_room.flywheel.impl;

import com.mojang.brigadier.Command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public final class FlwCommands {
    private FlwCommands() {
    }

    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        register(event);
    }

    public static void register(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("flywheel");

        command.then(Commands.literal("backend")
                             .executes(context -> {
                                 Backend backend = BackendManager.currentBackend();
                                 String idStr = Backend.REGISTRY.getIdOrThrow(backend)
                                                                .toString();
                                 sendMessage(context.getSource(),
                                         Component.translatable("command.flywheel.backend.get", idStr));
                                 return Command.SINGLE_SUCCESS;
                             })
                             .then(Commands.literal("DEFAULT")
                                           .executes(context -> {
                                               NeoForgeFlwConfig.INSTANCE.setBackendString(
                                                       FlwConfig.DEFAULT_BACKEND_STR);

                                               // Reload renderers so we can report the actual backend.
                                               reloadRenderers();

                                               Backend actualBackend = BackendManager.currentBackend();
                                               String actualIdStr = Backend.REGISTRY.getIdOrThrow(actualBackend)
                                                                                    .toString();
                                               sendMessage(context.getSource(),
                                                       Component.translatable("command.flywheel.backend.set",
                                                               actualIdStr));
                                               return Command.SINGLE_SUCCESS;
                                           }))
                             .then(Commands.argument("id", BackendArgument.INSTANCE)
                                           .executes(context -> {
                                               Backend requestedBackend = context.getArgument("id", Backend.class);
                                               String requestedIdStr = Backend.REGISTRY.getIdOrThrow(requestedBackend)
                                                                                       .toString();
                                               NeoForgeFlwConfig.INSTANCE.setBackendString(requestedIdStr);

                                               // Reload renderers so we can report the actual backend.
                                               reloadRenderers();

                                               Backend actualBackend = BackendManager.currentBackend();
                                               if (actualBackend != requestedBackend) {
                                                   sendFailure(context.getSource(), Component.translatable(
                                                           "command.flywheel.backend.set.unavailable", requestedIdStr));
                                               }

                                               String actualIdStr = Backend.REGISTRY.getIdOrThrow(actualBackend)
                                                                                    .toString();
                                               sendMessage(context.getSource(),
                                                       Component.translatable("command.flywheel.backend.set",
                                                               actualIdStr));
                                               return Command.SINGLE_SUCCESS;
                                           })));

        command.then(Commands.literal("limitUpdates")
                             .executes(context -> {
                                 if (NeoForgeFlwConfig.INSTANCE.limitUpdates()) {
                                     sendMessage(context.getSource(),
                                             Component.translatable("command.flywheel.limit_updates.get.on"));
                                 } else {
                                     sendMessage(context.getSource(),
                                             Component.translatable("command.flywheel.limit_updates.get.off"));
                                 }
                                 return Command.SINGLE_SUCCESS;
                             })
                             .then(Commands.literal("on")
                                           .executes(context -> {
                                               NeoForgeFlwConfig.INSTANCE.setLimitUpdates(true);
                                               sendMessage(context.getSource(),
                                                       Component.translatable("command.flywheel.limit_updates.set.on"));
                                               reloadRenderers();
                                               return Command.SINGLE_SUCCESS;
                                           }))
                             .then(Commands.literal("off")
                                           .executes(context -> {
                                               NeoForgeFlwConfig.INSTANCE.setLimitUpdates(false);
                                               sendMessage(context.getSource(), Component.translatable(
                                                       "command.flywheel.limit_updates.set.off"));
                                               reloadRenderers();
                                               return Command.SINGLE_SUCCESS;
                                           })));

        command.then(Commands.literal("lightSmoothness")
                             .then(Commands.argument("mode", LightSmoothnessArgument.INSTANCE)
                                           .executes(context -> {
                                               LightSmoothness oldValue = NeoForgeFlwConfig.INSTANCE.lightSmoothness();
                                               LightSmoothness newValue = context.getArgument("mode",
                                                       LightSmoothness.class);

                                               if (oldValue != newValue) {
                                                   NeoForgeFlwConfig.INSTANCE.setLightSmoothness(newValue);
                                               }
                                               return Command.SINGLE_SUCCESS;
                                           })));

        command.then(Commands.literal("terrain")
                             .then(Commands.literal("off")
                                           .executes(context -> {
                                               NeoForgeFlwConfig.INSTANCE.setTerrainMode(TerrainMode.OFF);
                                               reloadRenderers();
                                               sendMessage(context.getSource(),
                                                       FlwCommandText.terrainSet(TerrainMode.OFF));
                                               return Command.SINGLE_SUCCESS;
                                           }))
                             .then(Commands.literal("translucent")
                                           .executes(context -> {
                                               NeoForgeFlwConfig.INSTANCE.setTerrainMode(TerrainMode.TRANSLUCENT_OIT);
                                               reloadRenderers();
                                               sendMessage(context.getSource(),
                                                       FlwCommandText.terrainSet(TerrainMode.TRANSLUCENT_OIT));
                                               return Command.SINGLE_SUCCESS;
                                           }))
                             .then(Commands.literal("opaque")
                                           .executes(context -> {
                                               if (!SodiumCompat.ACTIVE) {
                                                   context.getSource().sendFailure(
                                                           FlwCommandText.terrainRequiresSodium(TerrainMode.OPAQUE));
                                                   return 0;
                                               }
                                               NeoForgeFlwConfig.INSTANCE.setTerrainMode(TerrainMode.OPAQUE);
                                               reloadRenderers();
                                               sendMessage(context.getSource(),
                                                       FlwCommandText.terrainSet(TerrainMode.OPAQUE));
                                               return Command.SINGLE_SUCCESS;
                                           }))
                             .then(Commands.literal("full")
                                           .executes(context -> {
                                               if (!SodiumCompat.ACTIVE) {
                                                   context.getSource()
                                                          .sendFailure(FlwCommandText.terrainRequiresSodium(
                                                                  TerrainMode.FULL));
                                                   return 0;
                                               }
                                               NeoForgeFlwConfig.INSTANCE.setTerrainMode(TerrainMode.FULL);
                                               reloadRenderers();
                                               sendMessage(context.getSource(),
                                                       FlwCommandText.terrainSet(TerrainMode.FULL));
                                               return Command.SINGLE_SUCCESS;
                                           })));

        command.then(createOitCommand());

        command.then(createDebugCommand());

        event.getDispatcher().register(command);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createOitCommand() {
        var oit = Commands.literal("oit")
                          .executes(context -> {
                              sendMessage(context.getSource(), OitConfig.statusComponent());
                              return Command.SINGLE_SUCCESS;
                          });
        var modeCmd = Commands.literal("mode");
        for (OitConfig.Path p : OitConfig.Path.values()) {
            modeCmd.then(Commands.literal(p.getSerializedName())
                                 .executes(context -> {
                                     OitConfig.setPath(p);
                                     sendMessage(context.getSource(), OitConfig.statusComponent());
                                     return Command.SINGLE_SUCCESS;
                                 }));
        }
        oit.then(modeCmd);
        oit.then(Commands.literal("layers")
                         .then(Commands.argument("n", IntegerArgumentType.integer(0, OitConfig.MAX_LAYERS))
                                       .executes(context -> {
                                           int n = IntegerArgumentType.getInteger(context, "n");
                                           var mode = OitConfig.setLayersForEffective(n);
                                           if (mode == null) {
                                               sendFailure(context.getSource(),
                                                       Component.translatable("command.flywheel.oit.no_insert"));
                                               return 0;
                                           }
                                           sendMessage(context.getSource(), OitConfig.statusComponent());
                                           return Command.SINGLE_SUCCESS;
                                       })));
        oit.then(Commands.literal("reset")
                         .executes(context -> {
                             OitConfig.resetLayers();
                             sendMessage(context.getSource(), OitConfig.statusComponent());
                             return Command.SINGLE_SUCCESS;
                         }));
        #if MC < 26.3
        oit.then(Commands.literal("exactweather")
                         .then(Commands.argument("value", BoolArgumentType.bool())
                                       .executes(context -> {
                                           OitConfig.setExactFabulous(BoolArgumentType.getBool(context, "value"));
                                           sendMessage(context.getSource(), OitConfig.statusComponent());
                                           return Command.SINGLE_SUCCESS;
                                       })));
        #endif
        return oit;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createDebugCommand() {
        var debug = Commands.literal("debug");

        debug.then(Commands.literal("crumbling")
                           .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                         .then(Commands.argument("stage", IntegerArgumentType.integer(0, 9))
                                                       .executes(context -> {
                                                           Entity executor = context.getSource()
                                                                                    .getEntity();

                                                           if (executor == null) {
                                                               return 0;
                                                           }

                                                           BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
                                                           int value = IntegerArgumentType.getInteger(context, "stage");

                                                           executor.level()
                                                                   .destroyBlockProgress(executor.getId(), pos, value);

                                                           return Command.SINGLE_SUCCESS;
                                                       }))));

        debug.then(Commands.literal("shader")
                           .then(Commands.argument("mode", DebugModeArgument.INSTANCE)
                                         .executes(context -> {
                                             DebugMode mode = context.getArgument("mode", DebugMode.class);
                                             FrameUniforms.debugMode(mode);
                                             return Command.SINGLE_SUCCESS;
                                         })));

        debug.then(Commands.literal("frustum")
                           .then(Commands.literal("capture")
                                         .executes(_ -> {
                                             FrameUniforms.captureFrustum();
                                             return Command.SINGLE_SUCCESS;
                                         }))
                           .then(Commands.literal("unpause")
                                         .executes(_ -> {
                                             FrameUniforms.unpauseFrustum();
                                             return Command.SINGLE_SUCCESS;
                                         })));

        debug.then(Commands.literal("lightSections")
                           .then(Commands.literal("on")
                                         .executes(_ -> {
                                             BackendDebugFlags.LIGHT_STORAGE_VIEW = true;
                                             return Command.SINGLE_SUCCESS;
                                         }))
                           .then(Commands.literal("off")
                                         .executes(_ -> {
                                             BackendDebugFlags.LIGHT_STORAGE_VIEW = false;
                                             return Command.SINGLE_SUCCESS;
                                         })));

        debug.then(Commands.literal("oit")
                           .then(Commands.literal("on")
                                         .executes(_ -> {
                                             BackendDebugFlags.SKIP_OIT = false;
                                             return Command.SINGLE_SUCCESS;
                                         }))
                           .then(Commands.literal("off")
                                         .executes(_ -> {
                                             BackendDebugFlags.SKIP_OIT = true;
                                             return Command.SINGLE_SUCCESS;
                                         })));

        #if MC < 26.3
        debug.then(Commands.literal("fabulousLayers")
                           .then(Commands.literal("on")
                                         .executes(context -> {
                                             ImplDebugFlags.FABULOUS_LAYER_VIEW = true;
                                             sendMessage(context.getSource(), Component.translatable(
                                                     "command.flywheel.debug.fabulous_layers.on"));
                                             return Command.SINGLE_SUCCESS;
                                         }))
                           .then(Commands.literal("off")
                                         .executes(context -> {
                                             ImplDebugFlags.FABULOUS_LAYER_VIEW = false;
                                             sendMessage(context.getSource(),
                                                     Component.translatable(
                                                             "command.flywheel.debug.fabulous_layers.off"));
                                             return Command.SINGLE_SUCCESS;
                                         })));
        #endif

        debug.then(Commands.literal("sodiumCull")
                           .then(Commands.literal("on")
                                         .executes(_ -> {
                                             TerrainDebug.SODIUM_CULL = true;
                                             return Command.SINGLE_SUCCESS;
                                         }))
                           .then(Commands.literal("off")
                                         .executes(_ -> {
                                             TerrainDebug.SODIUM_CULL = false;
                                             return Command.SINGLE_SUCCESS;
                                         })));

        debug.then(Commands.literal("pauseUpdates")
                           .then(Commands.literal("on")
                                         .executes(_ -> {
                                             ImplDebugFlags.PAUSE_UPDATES = true;
                                             return Command.SINGLE_SUCCESS;
                                         }))
                           .then(Commands.literal("off")
                                         .executes(_ -> {
                                             ImplDebugFlags.PAUSE_UPDATES = false;
                                             return Command.SINGLE_SUCCESS;
                                         })));

        debug.then(Commands.literal("terrainHiZ")
                           .then(Commands.literal("on")
                                         .executes(context -> {
                                             TerrainDebug.HIZ_ENABLED = true;
                                             FlwCommandText.glOnlyFeedback(
                                                     message -> sendMessage(context.getSource(), message));
                                             return Command.SINGLE_SUCCESS;
                                         }))
                           .then(Commands.literal("off")
                                         .executes(context -> {
                                             TerrainDebug.HIZ_ENABLED = false;
                                             FlwCommandText.glOnlyFeedback(
                                                     message -> sendMessage(context.getSource(), message));
                                             return Command.SINGLE_SUCCESS;
                                         })));

        debug.then(Commands.literal("gpuTimer")
                           .then(Commands.literal("on")
                                         .executes(context -> {
                                             FlwCommandText.gpuTimerState(GpuTimer.setEnabled(true),
                                                     message -> sendMessage(context.getSource(), message));
                                             return Command.SINGLE_SUCCESS;
                                         }))
                           .then(Commands.literal("off")
                                         .executes(context -> {
                                             FlwCommandText.gpuTimerState(GpuTimer.setEnabled(false),
                                                     message -> sendMessage(context.getSource(), message));
                                             return Command.SINGLE_SUCCESS;
                                         }))
                           .then(Commands.literal("once")
                                         .executes(context -> {
                                             FlwCommandText.gpuTimerState(GpuTimer.captureOnce(),
                                                     message -> sendMessage(context.getSource(), message));
                                             return Command.SINGLE_SUCCESS;
                                         }))
                           .then(Commands.literal("summary")
                                         .executes(context -> {
                                             context.getSource()
                                                    .sendSystemMessage(GpuTimer.commandReport());
                                             return Command.SINGLE_SUCCESS;
                                         })));

        debug.then(Commands.literal("info")
                           .executes(context -> {
                               context.getSource()
                                      .sendSystemMessage(FlwDebugInfo.getDebugCommandInfo());
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


    private static void sendMessage(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, true);
    }

    private static void sendFailure(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }
}
