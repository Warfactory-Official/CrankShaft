package dev.engine_room.flywheel.impl;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.backend.BackendDebugFlags;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDebug;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.GpuTimer;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
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
                    sendMessage(context.getSource(), Component.translatable("command.flywheel.backend.get", idStr));
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("DEFAULT")
                    .executes(context -> {
                        NeoForgeFlwConfig.INSTANCE.setBackendString(FlwConfig.DEFAULT_BACKEND_STR);

                        // Reload renderers so we can report the actual backend.
                        reloadRenderers();

                        Backend actualBackend = BackendManager.currentBackend();
                        String actualIdStr = Backend.REGISTRY.getIdOrThrow(actualBackend)
                                .toString();
                        sendMessage(context.getSource(), Component.translatable("command.flywheel.backend.set", actualIdStr));
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
                            sendFailure(context.getSource(), Component.translatable("command.flywheel.backend.set.unavailable", requestedIdStr));
                        }

                        String actualIdStr = Backend.REGISTRY.getIdOrThrow(actualBackend)
                                .toString();
                        sendMessage(context.getSource(), Component.translatable("command.flywheel.backend.set", actualIdStr));
                        return Command.SINGLE_SUCCESS;
                    })));

        command.then(Commands.literal("limitUpdates")
                .executes(context -> {
                    if (NeoForgeFlwConfig.INSTANCE.limitUpdates()) {
                        sendMessage(context.getSource(), Component.translatable("command.flywheel.limit_updates.get.on"));
                    } else {
                        sendMessage(context.getSource(), Component.translatable("command.flywheel.limit_updates.get.off"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("on")
                        .executes(context -> {
                            NeoForgeFlwConfig.INSTANCE.setLimitUpdates(true);
                            sendMessage(context.getSource(), Component.translatable("command.flywheel.limit_updates.set.on"));
                            reloadRenderers();
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            NeoForgeFlwConfig.INSTANCE.setLimitUpdates(false);
                            sendMessage(context.getSource(), Component.translatable("command.flywheel.limit_updates.set.off"));
                            reloadRenderers();
                            return Command.SINGLE_SUCCESS;
                        })));

        command.then(Commands.literal("lightSmoothness")
                .then(Commands.argument("mode", LightSmoothnessArgument.INSTANCE)
                        .executes(context -> {
                            LightSmoothness oldValue = NeoForgeFlwConfig.INSTANCE.lightSmoothness();
                            LightSmoothness newValue = context.getArgument("mode", LightSmoothness.class);

                            if (oldValue != newValue) {
                                NeoForgeFlwConfig.INSTANCE.setLightSmoothness(newValue);
                            }
                            return Command.SINGLE_SUCCESS;
                        })));

        command.then(Commands.literal("terrain")
                .then(Commands.literal("off")
                        .executes(context -> {
                            NeoForgeFlwConfig.INSTANCE.setTerrainMode(TerrainMode.OFF);
                            sendMessage(context.getSource(), Component.literal("terrain: off"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("translucent")
                        .executes(context -> {
                            NeoForgeFlwConfig.INSTANCE.setTerrainMode(TerrainMode.TRANSLUCENT_OIT);
                            sendMessage(context.getSource(), Component.literal("terrain: translucent"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("opaque")
                        .executes(context -> {
                            if (!SodiumCompat.isSodiumActive()) {
                                context.getSource().sendFailure(Component.literal("terrain opaque requires Sodium "
                                        + "(opaque MDI takeover reads Sodium's live geometry arena). Not applied."));
                                return 0;
                            }
                            NeoForgeFlwConfig.INSTANCE.setTerrainMode(TerrainMode.OPAQUE);
                            sendMessage(context.getSource(), Component.literal("terrain: opaque -- flywheel culls + "
                                    + "draws opaque terrain, Sodium keeps translucent (no terrain OIT; culling A/B mode)"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("full")
                        .executes(context -> {
                            if (!SodiumCompat.isSodiumActive()) {
                                context.getSource().sendFailure(Component.literal("terrain full requires Sodium "
                                        + "(opaque MDI takeover reads Sodium's live geometry arena). Not applied."));
                                return 0;
                            }
                            NeoForgeFlwConfig.INSTANCE.setTerrainMode(TerrainMode.FULL);
                            sendMessage(context.getSource(), Component.literal("terrain: full"));
                            return Command.SINGLE_SUCCESS;
                        })));

        command.then(Commands.literal("ownGeometry")
                .then(Commands.literal("on")
                        .executes(context -> {
                            NeoForgeFlwConfig.INSTANCE.setOwnGeometry(true);
                            reloadRenderers();
                            sendMessage(context.getSource(), Component.literal("ownGeometry: on -- mesh tiers copy Sodium's arena into owned buffers (renderers reloaded)"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            NeoForgeFlwConfig.INSTANCE.setOwnGeometry(false);
                            reloadRenderers();
                            sendMessage(context.getSource(), Component.literal("ownGeometry: off -- mesh tiers alias Sodium's live arena (renderers reloaded)"));
                            return Command.SINGLE_SUCCESS;
                        })));

        command.then(createOitCommand());

        command.then(createStressCommand());

        command.then(createDebugCommand());

        event.getDispatcher().register(command);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createOitCommand() {
        var oit = Commands.literal("oit")
                .executes(context -> {
                    sendMessage(context.getSource(), Component.literal(OitConfig.status()));
                    return Command.SINGLE_SUCCESS;
                });
        var modeCmd = Commands.literal("mode");
        for (OitConfig.Path p : OitConfig.Path.values()) {
            modeCmd.then(Commands.literal(p.name().toLowerCase(java.util.Locale.ROOT))
                    .executes(context -> {
                        OitConfig.setPath(p);
                        sendMessage(context.getSource(), Component.literal(OitConfig.status() + insertModeGlNote(p)));
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
                                sendFailure(context.getSource(), Component.literal("No insert OIT mode active (wavelet has no layers)."));
                                return 0;
                            }
                            sendMessage(context.getSource(), Component.literal(OitConfig.status()));
                            return Command.SINGLE_SUCCESS;
                        })));
        oit.then(Commands.literal("reset")
                .executes(context -> {
                    OitConfig.resetLayers();
                    sendMessage(context.getSource(), Component.literal(OitConfig.status()));
                    return Command.SINGLE_SUCCESS;
                }));
        oit.then(Commands.literal("exactweather")
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> {
                            OitConfig.setExactFabulous(BoolArgumentType.getBool(context, "value"));
                            sendMessage(context.getSource(), Component.literal(OitConfig.status()));
                            return Command.SINGLE_SUCCESS;
                        })));
        return oit;
    }

    private static final SuggestionProvider<CommandSourceStack> STRESS_TYPE_SUGGESTIONS =
            (context, builder) -> SharedSuggestionProvider.suggestResource(FlwStress.entityTypeIds(), builder);

    private static LiteralArgumentBuilder<CommandSourceStack> createStressCommand() {
        var stress = Commands.literal("stress");

        stress.then(Commands.literal("spawn")
                .then(Commands.argument("count", IntegerArgumentType.integer(1, FlwStress.STRESS_MAX))
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(FlwStress.STRESS_COUNTS, builder))
                        .executes(context -> {
                            int count = IntegerArgumentType.getInteger(context, "count");
                            sendMessage(context.getSource(), FlwStress.spawn(count, "minecraft:pig", 2.0D));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("type", StringArgumentType.string())
                                .suggests(STRESS_TYPE_SUGGESTIONS)
                                .executes(context -> {
                                    int count = IntegerArgumentType.getInteger(context, "count");
                                    String type = StringArgumentType.getString(context, "type");
                                    sendMessage(context.getSource(), FlwStress.spawn(count, type, 2.0D));
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("spacing", DoubleArgumentType.doubleArg(0.0D))
                                        .executes(context -> {
                                            int count = IntegerArgumentType.getInteger(context, "count");
                                            String type = StringArgumentType.getString(context, "type");
                                            double spacing = DoubleArgumentType.getDouble(context, "spacing");
                                            sendMessage(context.getSource(), FlwStress.spawn(count, type, spacing));
                                            return Command.SINGLE_SUCCESS;
                                        })))));

        stress.then(Commands.literal("clear")
                .executes(context -> {
                    sendMessage(context.getSource(), FlwStress.clear());
                    return Command.SINGLE_SUCCESS;
                }));

        stress.then(Commands.literal("chests")
                .executes(context -> {
                    sendMessage(context.getSource(), FlwStress.spawnChests());
                    return Command.SINGLE_SUCCESS;
                }));

        stress.then(Commands.literal("fabulous")
                .executes(context -> {
                    sendMessage(context.getSource(), FlwStress.buildFabulousRig());
                    return Command.SINGLE_SUCCESS;
                }));

        return stress;
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
                        .executes(context -> {
                            FrameUniforms.captureFrustum();
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("unpause")
                        .executes(context -> {
                            FrameUniforms.unpauseFrustum();
                            return Command.SINGLE_SUCCESS;
                        })));

        debug.then(Commands.literal("lightSections")
                .then(Commands.literal("on")
                        .executes(context -> {
                            BackendDebugFlags.LIGHT_STORAGE_VIEW = true;
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            BackendDebugFlags.LIGHT_STORAGE_VIEW = false;
                            return Command.SINGLE_SUCCESS;
                        })));

        debug.then(Commands.literal("oit")
                .then(Commands.literal("on")
                        .executes(context -> {
                            BackendDebugFlags.SKIP_OIT = false;
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            BackendDebugFlags.SKIP_OIT = true;
                            return Command.SINGLE_SUCCESS;
                        })));

        debug.then(Commands.literal("fabulousLayers")
                .then(Commands.literal("on")
                        .executes(context -> {
                            ImplDebugFlags.FABULOUS_LAYER_VIEW = true;
                            sendMessage(context.getSource(), Component.literal("Raw item/particle layer overlay: ON (alpha-blit over the frame, bypassing the OIT replay)"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            ImplDebugFlags.FABULOUS_LAYER_VIEW = false;
                            sendMessage(context.getSource(), Component.literal("Raw item/particle layer overlay: OFF"));
                            return Command.SINGLE_SUCCESS;
                        })));

        debug.then(Commands.literal("sodiumCull")
                .then(Commands.literal("on")
                        .executes(context -> {
                            TerrainDebug.SODIUM_CULL = true;
                            sendMessage(context.getSource(), Component.literal("Sodium render-list cull cancel: ON (single-cull MDI; needs /flywheel terrain full + INDIRECT)"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            TerrainDebug.SODIUM_CULL = false;
                            sendMessage(context.getSource(), Component.literal("Sodium render-list cull cancel: OFF (double-cull baseline)"));
                            return Command.SINGLE_SUCCESS;
                        })));

        debug.then(Commands.literal("pauseUpdates")
                .then(Commands.literal("on")
                        .executes(context -> {
                            ImplDebugFlags.PAUSE_UPDATES = true;
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            ImplDebugFlags.PAUSE_UPDATES = false;
                            return Command.SINGLE_SUCCESS;
                        })));

        debug.then(Commands.literal("terrainHiZ")
                .then(Commands.literal("on")
                        .executes(context -> {
                            TerrainDebug.HIZ_ENABLED = true;
                            sendMessage(context.getSource(), Component.literal("Terrain HiZ occlusion cull: ON" + glOnlyNote()));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            TerrainDebug.HIZ_ENABLED = false;
                            sendMessage(context.getSource(), Component.literal("Terrain HiZ occlusion cull: OFF (same image, no occlusion savings)" + glOnlyNote()));
                            return Command.SINGLE_SUCCESS;
                        })));

        debug.then(Commands.literal("terrainHiZProbe")
                .then(Commands.literal("on")
                        .executes(context -> {
                            TerrainDebug.HIZ_PROBE = true;
                            sendMessage(context.getSource(), Component.literal("Terrain HiZ probe: ON -- NOTE: currently a no-op (the probe has no read-site on this build)."));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            TerrainDebug.HIZ_PROBE = false;
                            sendMessage(context.getSource(), Component.literal("Terrain HiZ probe: OFF"));
                            return Command.SINGLE_SUCCESS;
                        })));

        debug.then(Commands.literal("terrainBuilderDiff")
                .then(Commands.literal("on")
                        .executes(context -> {
                            TerrainDebug.DEBUG_VALIDATE_GPU_BUILDER = true;
                            sendMessage(context.getSource(), Component.literal("Terrain GPU-builder validation: ON (throws on GPU/CPU mismatch)"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            TerrainDebug.DEBUG_VALIDATE_GPU_BUILDER = false;
                            sendMessage(context.getSource(), Component.literal("Terrain GPU-builder validation: OFF"));
                            return Command.SINGLE_SUCCESS;
                        })));

        debug.then(Commands.literal("gpuTimer")
                .then(Commands.literal("on")
                        .executes(context -> {
                            sendMessage(context.getSource(), gpuTimerState(GpuTimer.setEnabled(true)));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("off")
                        .executes(context -> {
                            sendMessage(context.getSource(), gpuTimerState(GpuTimer.setEnabled(false)));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("once")
                        .executes(context -> {
                            sendMessage(context.getSource(), gpuTimerState(GpuTimer.captureOnce()));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("summary")
                        .executes(context -> {
                            context.getSource().sendSystemMessage(Component.literal(GpuTimer.commandReport()));
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

    private static Component gpuTimerState(GpuTimer.State state) {
        return Component.literal(switch (state) {
            case ENABLED -> "GPU timer: ON";
            case DISABLED -> "GPU timer: OFF";
            case ARMED -> "GPU timer: armed for one completed frame";
            case UNAVAILABLE -> "GPU timer unavailable on the active backend";
        });
    }

    private static void reloadRenderers() {
        Minecraft mc = Minecraft.getInstance();
        mc.levelExtractor.allChanged();
        if (mc.level != null) {
            BackendManagerImpl.onReloadLevelRenderer(mc.level);
        }
    }

    private static String glOnlyNote() {
        return VkContext.isVulkanHost() ? " (no effect on the active Vulkan backend; GL-only)" : "";
    }

    private static String insertModeGlNote(OitConfig.Path p) {
        boolean insert = p == OitConfig.Path.KBUFFER || p == OitConfig.Path.MLAB || p == OitConfig.Path.ABUFFER;
        return insert && !VkContext.isVulkanHost()
                ? "\nNOTE: on GL, insert OIT runs on the gpu-driven backend (gl_indirect / gl_mesh_shader); the instancing backend uses the wavelet chain."
                : "";
    }

    private static void sendMessage(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, true);
    }

    private static void sendFailure(CommandSourceStack source, Component message) {
        source.sendFailure(message);
    }
}
