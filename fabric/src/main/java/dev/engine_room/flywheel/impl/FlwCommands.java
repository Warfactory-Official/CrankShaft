package dev.engine_room.flywheel.impl;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
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
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class FlwCommands {
	private FlwCommands() {
	}

	public static void registerClientCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext buildContext) {
		LiteralArgumentBuilder<FabricClientCommandSource> command = ClientCommands.literal("flywheel");

		command.then(ClientCommands.literal("backend")
				.executes(context -> {
					Backend backend = BackendManager.currentBackend();
					String idStr = Backend.REGISTRY.getIdOrThrow(backend)
							.toString();
					context.getSource()
							.sendFeedback(Component.translatable("command.flywheel.backend.get", idStr));
					return Command.SINGLE_SUCCESS;
				})
				.then(ClientCommands.literal("DEFAULT")
					.executes(context -> {
						FabricFlwConfig.INSTANCE.setBackendString(FlwConfig.DEFAULT_BACKEND_STR);

						// Reload renderers so we can report the actual backend.
						reloadRenderers();

						Backend actualBackend = BackendManager.currentBackend();
						String actualIdStr = Backend.REGISTRY.getIdOrThrow(actualBackend)
								.toString();
						context.getSource()
								.sendFeedback(Component.translatable("command.flywheel.backend.set", actualIdStr));
						return Command.SINGLE_SUCCESS;
					}))
				.then(ClientCommands.argument("id", BackendArgument.INSTANCE)
					.executes(context -> {
						Backend requestedBackend = context.getArgument("id", Backend.class);
						String requestedIdStr = Backend.REGISTRY.getIdOrThrow(requestedBackend)
								.toString();
						FabricFlwConfig.INSTANCE.setBackendString(requestedIdStr);

						// Reload renderers so we can report the actual backend.
						reloadRenderers();

						Backend actualBackend = BackendManager.currentBackend();
						if (actualBackend != requestedBackend) {
							context.getSource()
									.sendError(Component.translatable("command.flywheel.backend.set.unavailable", requestedIdStr));
						}

						String actualIdStr = Backend.REGISTRY.getIdOrThrow(actualBackend)
								.toString();
						context.getSource()
								.sendFeedback(Component.translatable("command.flywheel.backend.set", actualIdStr));
						return Command.SINGLE_SUCCESS;
					})));

		command.then(ClientCommands.literal("limitUpdates")
				.executes(context -> {
					if (FabricFlwConfig.INSTANCE.limitUpdates()) {
						context.getSource()
								.sendFeedback(Component.translatable("command.flywheel.limit_updates.get.on"));
					} else {
						context.getSource()
								.sendFeedback(Component.translatable("command.flywheel.limit_updates.get.off"));
					}
					return Command.SINGLE_SUCCESS;
				})
				.then(ClientCommands.literal("on")
						.executes(context -> {
							FabricFlwConfig.INSTANCE.setLimitUpdates(true);
							context.getSource()
									.sendFeedback(Component.translatable("command.flywheel.limit_updates.set.on"));
							reloadRenderers();
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							FabricFlwConfig.INSTANCE.setLimitUpdates(false);
							context.getSource()
									.sendFeedback(Component.translatable("command.flywheel.limit_updates.set.off"));
							reloadRenderers();
							return Command.SINGLE_SUCCESS;
						})));

		command.then(ClientCommands.literal("lightSmoothness")
				.then(ClientCommands.argument("mode", LightSmoothnessArgument.INSTANCE)
						.executes(context -> {
							LightSmoothness oldValue = FabricFlwConfig.INSTANCE.backendConfig.lightSmoothness;
							LightSmoothness newValue = context.getArgument("mode", LightSmoothness.class);

							if (oldValue != newValue) {
								FabricFlwConfig.INSTANCE.setLightSmoothness(newValue);
							}
							return Command.SINGLE_SUCCESS;
						})));

		command.then(ClientCommands.literal("terrain")
				.then(ClientCommands.literal("off")
						.executes(context -> {
							FabricFlwConfig.INSTANCE.setTerrainMode(TerrainMode.OFF);
							context.getSource().sendFeedback(Component.literal("terrain: off"));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("translucent")
						.executes(context -> {
							FabricFlwConfig.INSTANCE.setTerrainMode(TerrainMode.TRANSLUCENT_OIT);
							context.getSource().sendFeedback(Component.literal("terrain: translucent"));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("opaque")
						.executes(context -> {
							if (!SodiumCompat.isSodiumActive()) {
								context.getSource().sendError(Component.literal("terrain opaque requires Sodium "
										+ "(opaque MDI takeover reads Sodium's live geometry arena). Not applied."));
								return 0;
							}
							FabricFlwConfig.INSTANCE.setTerrainMode(TerrainMode.OPAQUE);
							context.getSource().sendFeedback(Component.literal("terrain: opaque -- flywheel culls + "
									+ "draws opaque terrain, Sodium keeps translucent (no terrain OIT; culling A/B mode)"));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("full")
						.executes(context -> {
							if (!SodiumCompat.isSodiumActive()) {
								context.getSource().sendError(Component.literal("terrain full requires Sodium "
										+ "(opaque MDI takeover reads Sodium's live geometry arena). Not applied."));
								return 0;
							}
							FabricFlwConfig.INSTANCE.setTerrainMode(TerrainMode.FULL);
							context.getSource().sendFeedback(Component.literal("terrain: full"));
							return Command.SINGLE_SUCCESS;
						})));

		command.then(ClientCommands.literal("ownGeometry")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							FabricFlwConfig.INSTANCE.setOwnGeometry(true);
							reloadRenderers();
							context.getSource().sendFeedback(Component.literal("ownGeometry: on -- mesh tiers copy Sodium's arena into owned buffers (renderers reloaded)"));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							FabricFlwConfig.INSTANCE.setOwnGeometry(false);
							reloadRenderers();
							context.getSource().sendFeedback(Component.literal("ownGeometry: off -- mesh tiers alias Sodium's live arena (renderers reloaded)"));
							return Command.SINGLE_SUCCESS;
						})));

		command.then(createOitCommand());

		command.then(createStressCommand());

		command.then(createDebugCommand());

		dispatcher.register(command);
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> createOitCommand() {
		var oit = ClientCommands.literal("oit")
				.executes(context -> {
					context.getSource().sendFeedback(Component.literal(OitConfig.status()));
					return Command.SINGLE_SUCCESS;
				});
		var modeCmd = ClientCommands.literal("mode");
		for (OitConfig.Path p : OitConfig.Path.values()) {
			modeCmd.then(ClientCommands.literal(p.name().toLowerCase(java.util.Locale.ROOT))
					.executes(context -> {
						OitConfig.setPath(p);
						context.getSource().sendFeedback(Component.literal(OitConfig.status() + insertModeGlNote(p)));
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
								context.getSource().sendError(Component.literal("No insert OIT mode active (wavelet has no layers)."));
								return 0;
							}
							context.getSource().sendFeedback(Component.literal(OitConfig.status()));
							return Command.SINGLE_SUCCESS;
						})));
		oit.then(ClientCommands.literal("reset")
				.executes(context -> {
					OitConfig.resetLayers();
					context.getSource().sendFeedback(Component.literal(OitConfig.status()));
					return Command.SINGLE_SUCCESS;
				}));
		oit.then(ClientCommands.literal("exactweather")
				.then(ClientCommands.argument("value", BoolArgumentType.bool())
						.executes(context -> {
							OitConfig.setExactFabulous(BoolArgumentType.getBool(context, "value"));
							context.getSource().sendFeedback(Component.literal(OitConfig.status()));
							return Command.SINGLE_SUCCESS;
						})));
		return oit;
	}

	private static final SuggestionProvider<FabricClientCommandSource> STRESS_TYPE_SUGGESTIONS =
			(context, builder) -> SharedSuggestionProvider.suggestResource(FlwStress.entityTypeIds(), builder);

	private static LiteralArgumentBuilder<FabricClientCommandSource> createStressCommand() {
		var stress = ClientCommands.literal("stress");

		stress.then(ClientCommands.literal("spawn")
				.then(ClientCommands.argument("count", IntegerArgumentType.integer(1, FlwStress.STRESS_MAX))
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(FlwStress.STRESS_COUNTS, builder))
						.executes(context -> {
							int count = IntegerArgumentType.getInteger(context, "count");
							context.getSource().sendFeedback(FlwStress.spawn(count, "minecraft:pig", 2.0D));
							return Command.SINGLE_SUCCESS;
						})
						.then(ClientCommands.argument("type", StringArgumentType.string())
								.suggests(STRESS_TYPE_SUGGESTIONS)
								.executes(context -> {
									int count = IntegerArgumentType.getInteger(context, "count");
									String type = StringArgumentType.getString(context, "type");
									context.getSource().sendFeedback(FlwStress.spawn(count, type, 2.0D));
									return Command.SINGLE_SUCCESS;
								})
								.then(ClientCommands.argument("spacing", DoubleArgumentType.doubleArg(0.0D))
										.executes(context -> {
											int count = IntegerArgumentType.getInteger(context, "count");
											String type = StringArgumentType.getString(context, "type");
											double spacing = DoubleArgumentType.getDouble(context, "spacing");
											context.getSource().sendFeedback(FlwStress.spawn(count, type, spacing));
											return Command.SINGLE_SUCCESS;
										})))));

		stress.then(ClientCommands.literal("clear")
				.executes(context -> {
					context.getSource().sendFeedback(FlwStress.clear());
					return Command.SINGLE_SUCCESS;
				}));

		stress.then(ClientCommands.literal("chests")
				.executes(context -> {
					context.getSource().sendFeedback(FlwStress.spawnChests());
					return Command.SINGLE_SUCCESS;
				}));

		stress.then(ClientCommands.literal("fabulous")
				.executes(context -> {
					context.getSource().sendFeedback(FlwStress.buildFabulousRig());
					return Command.SINGLE_SUCCESS;
				}));

		return stress;
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> createDebugCommand() {
		var debug = ClientCommands.literal("debug");

		debug.then(ClientCommands.literal("crumbling")
				.then(ClientCommands.argument("pos", BlockPosArgument.blockPos())
						.then(ClientCommands.argument("stage", IntegerArgumentType.integer(0, 9))
								.executes(context -> {
									Entity executor = context.getSource()
											.getEntity();

									if (executor == null) {
										return 0;
									}

									BlockPos pos = getBlockPos(context, "pos");
									int value = IntegerArgumentType.getInteger(context, "stage");

									executor.level()
											.destroyBlockProgress(executor.getId(), pos, value);

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
						.executes(context -> {
							FrameUniforms.captureFrustum();
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("unpause")
						.executes(context -> {
							FrameUniforms.unpauseFrustum();
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("lightSections")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							BackendDebugFlags.LIGHT_STORAGE_VIEW = true;
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							BackendDebugFlags.LIGHT_STORAGE_VIEW = false;
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("oit")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							BackendDebugFlags.SKIP_OIT = false;
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							BackendDebugFlags.SKIP_OIT = true;
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("fabulousLayers")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							ImplDebugFlags.FABULOUS_LAYER_VIEW = true;
							context.getSource().sendFeedback(Component.literal("Raw item/particle layer overlay: ON (alpha-blit over the frame, bypassing the OIT replay)"));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							ImplDebugFlags.FABULOUS_LAYER_VIEW = false;
							context.getSource().sendFeedback(Component.literal("Raw item/particle layer overlay: OFF"));
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("sodiumCull")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							TerrainDebug.SODIUM_CULL = true;
							context.getSource().sendFeedback(Component.literal("Sodium render-list cull cancel: ON (single-cull MDI; needs /flywheel terrain full + INDIRECT)"));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							TerrainDebug.SODIUM_CULL = false;
							context.getSource().sendFeedback(Component.literal("Sodium render-list cull cancel: OFF (double-cull baseline)"));
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("pauseUpdates")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							ImplDebugFlags.PAUSE_UPDATES = true;
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							ImplDebugFlags.PAUSE_UPDATES = false;
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("terrainHiZ")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							TerrainDebug.HIZ_ENABLED = true;
							context.getSource().sendFeedback(Component.literal("Terrain HiZ occlusion cull: ON" + glOnlyNote()));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							TerrainDebug.HIZ_ENABLED = false;
							context.getSource().sendFeedback(Component.literal("Terrain HiZ occlusion cull: OFF (same image, no occlusion savings)" + glOnlyNote()));
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("terrainHiZProbe")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							TerrainDebug.HIZ_PROBE = true;
							context.getSource().sendFeedback(Component.literal("Terrain HiZ probe: ON -- NOTE: currently a no-op (the probe has no read-site on this build)."));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							TerrainDebug.HIZ_PROBE = false;
							context.getSource().sendFeedback(Component.literal("Terrain HiZ probe: OFF"));
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("terrainBuilderDiff")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							TerrainDebug.DEBUG_VALIDATE_GPU_BUILDER = true;
							context.getSource().sendFeedback(Component.literal("Terrain GPU-builder validation: ON (throws on GPU/CPU mismatch)"));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							TerrainDebug.DEBUG_VALIDATE_GPU_BUILDER = false;
							context.getSource().sendFeedback(Component.literal("Terrain GPU-builder validation: OFF"));
							return Command.SINGLE_SUCCESS;
						})));

		debug.then(ClientCommands.literal("gpuTimer")
				.then(ClientCommands.literal("on")
						.executes(context -> {
							context.getSource().sendFeedback(gpuTimerState(GpuTimer.setEnabled(true)));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("off")
						.executes(context -> {
							context.getSource().sendFeedback(gpuTimerState(GpuTimer.setEnabled(false)));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("once")
						.executes(context -> {
							context.getSource().sendFeedback(gpuTimerState(GpuTimer.captureOnce()));
							return Command.SINGLE_SUCCESS;
						}))
				.then(ClientCommands.literal("summary")
						.executes(context -> {
							context.getSource().sendFeedback(Component.literal(GpuTimer.commandReport()));
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

	private static Component gpuTimerState(GpuTimer.State state) {
		return Component.literal(switch (state) {
			case ENABLED -> "GPU timer: ON";
			case DISABLED -> "GPU timer: OFF";
			case ARMED -> "GPU timer: armed for one completed frame";
			case UNAVAILABLE -> "GPU timer unavailable on the active backend";
		});
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
