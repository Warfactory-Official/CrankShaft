package dev.engine_room.flywheel.impl.config;


import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.impl.FlwImplXplat;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.EnumOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loaded by Sodium's optional metadata entrypoint; registration and edits run on the client thread.
 */
public final class SodiumConfig implements ConfigEntryPoint {
    private static final Identifier RELOAD = id("reload_visuals");
    private static final Identifier SHADERPACK_RELOAD = id("reload_shaderpack");
    private static final StorageEventHandler STORAGE = () -> FlwConfig.INSTANCE.endBatch(true);

    private static <E extends Enum<E>> EnumOptionBuilder<E> enumOption(
            ConfigBuilder builder, String name, Class<E> type, E defaultValue, Function<E, Component> tooltip,
            Consumer<E> save, Supplier<E> load) {
        if (name.equals("terrain") || name.equals("oit") || name.equals("shaderpack_oit"))
            SodiumDropdowns.registerEnum(id(name), type);
        return builder.createEnumOption(id(name), type)
                      .setName(text(name))
                      .setTooltip(tooltip)
                      .setStorageHandler(STORAGE)
                      .setDefaultValue(defaultValue)
                      .setElementNameProvider(value -> text(name + "." + value.name().toLowerCase(Locale.ROOT)))
                      .setBinding(save, load);
    }

    private static BooleanOptionBuilder booleanOption(ConfigBuilder builder, String name, boolean defaultValue,
                                                      Consumer<Boolean> save, Supplier<Boolean> load) {
        return builder.createBooleanOption(id(name))
                      .setName(text(name))
                      .setTooltip(text(name + ".tooltip"))
                      .setStorageHandler(STORAGE)
                      .setDefaultValue(defaultValue)
                      .setBinding(save, load);
    }

    static void reloadRenderers() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != null) minecraft.levelExtractor.allChanged();
        BackendManagerImpl.reselect();
    }

    static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath("crankshaft", name);
    }

    static MutableComponent text(String name, Object... args) {
        return Component.translatable("flywheel.options." + name, args);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var config = FlwConfig.INSTANCE;
        var mod = builder.registerOwnModOptions();
        mod.registerFlagHook((_, _) -> reloadRenderers(), RELOAD);
        mod.registerFlagHook((_, _) -> {
            OitConfig.reloadShaderpackOit();
            reloadRenderers();
        }, SHADERPACK_RELOAD);

        mod.addPage(builder.createOptionPage()
                           .setName(text("rendering"))
                           .addOptionGroup(SodiumDropdowns.backendGroup(builder, config, STORAGE, RELOAD))
                           .addOptionGroup(builder.createOptionGroup()
                                                  .addOption(enumOption(builder, "terrain", TerrainMode.class,
                                                          TerrainMode.TRANSLUCENT_OIT,
                                                          SodiumOptionTooltips::terrainDescription,
                                                          config::setTerrainMode,
                                                          () -> config.backendConfig().terrainMode())
                                                          .setImpact(OptionImpact.VARIES).setFlags(RELOAD))
                                                  .addOption(
                                                          enumOption(builder, "light_smoothness", LightSmoothness.class,
                                                                  LightSmoothness.SMOOTH,
                                                                  value -> text("light_smoothness." + value.name()
                                                                                                           .toLowerCase(
                                                                                                                   Locale.ROOT) + ".tooltip"),
                                                                  config::setLightSmoothness,
                                                                  () -> config.backendConfig()
                                                                              .lightSmoothness())
                                                                  .setImpact(OptionImpact.MEDIUM).setFlags(RELOAD))));

        var oit = builder.createOptionGroup()
                         .addOption(enumOption(builder, "oit", OitConfig.Path.class, OitConfig.Path.AUTO,
                                 SodiumOptionTooltips::oitDescription, OitConfig::setPath, OitConfig::path)
                                 .setImpact(OptionImpact.HIGH));
        for (var path : OitConfig.Path.values()) {
            if (OitConfig.preset(path) == 0) continue;
            var name = "layers." + path.name().toLowerCase(Locale.ROOT);
            oit.addOption(builder.createIntegerOption(id(name))
                                 .setName(text(name))
                                 .setTooltip(text("layers.tooltip"))
                                 .setStorageHandler(STORAGE)
                                 .setDefaultValue(0)
                                 .setImpact(OptionImpact.MEDIUM)
                                 .setRange(0, OitConfig.MAX_LAYERS, 1)
                                 .setValueFormatter(value -> value == 0
                                         ? text("layers.preset", OitConfig.preset(path)) : Component.literal(
                                         Integer.toString(value)))
                                 .setBinding(value -> OitConfig.setLayers(path, value),
                                         () -> OitConfig.rawLayers(path)));
        }
        #if MC < 26.3
        oit.addOption(booleanOption(builder, "exact_weather", false,
                OitConfig::setExactFabulous, OitConfig::exactFabulous).setImpact(OptionImpact.HIGH));
        #endif
        mod.addPage(builder.createOptionPage().setName(text("transparency")).addOptionGroup(oit)
                           .addOptionGroup(builder.createOptionGroup().addOption(
                                   enumOption(builder, "shaderpack_oit", OitConfig.ShaderpackMode.class,
                                           OitConfig.ShaderpackMode.DEFERRED,
                                           value -> text("shaderpack_oit." + value.getSerializedName() + ".tooltip"),
                                           OitConfig::setShaderpackMode, OitConfig::shaderpackMode)
                                           .setImpact(OptionImpact.HIGH)
                                           .setFlags(SHADERPACK_RELOAD))));

        var processors = Runtime.getRuntime().availableProcessors();
        mod.addPage(builder.createOptionPage()
                           .setName(text("performance"))
                           .addOptionGroup(builder.createOptionGroup()
                                                  .addOption(booleanOption(builder, "limit_updates", true,
                                                          config::setLimitUpdates, config::limitUpdates)
                                                          .setImpact(OptionImpact.MEDIUM).setFlags(
                                                                  RELOAD))
                                                  .addOption(booleanOption(builder, "concurrent_extraction", true,
                                                          config::setConcurrentExtraction,
                                                          config::concurrentExtraction).setImpact(OptionImpact.VARIES)))
                           .addOptionGroup(builder.createOptionGroup()
                                                  .addOption(builder.createIntegerOption(id("worker_threads"))
                                                                    .setName(text("worker_threads"))
                                                                    .setTooltip(text("worker_threads.tooltip"))
                                                                    .setStorageHandler(STORAGE)
                                                                    .setDefaultValue(Math.max(1, processors - 1))
                                                                    .setImpact(OptionImpact.VARIES)
                                                                    .setRange(1, processors, 1)
                                                                    .setValueFormatter(value -> Component.literal(
                                                                            Integer.toString(value)))
                                                                    .setBinding(config::setWorkerThreads,
                                                                            config::workerThreadCount)
                                                                    .setFlags(OptionFlag.REQUIRES_GAME_RESTART))
                                                  .addOption(booleanOption(builder, "common_pool", false,
                                                          config::setUseCommonPool, config::useCommonPool)
                                                          .setImpact(OptionImpact.VARIES)
                                                          .setFlags(OptionFlag.REQUIRES_GAME_RESTART))));
        if (FlwImplXplat.INSTANCE.isModLoaded("vanillate")) {
            try {
                Class.forName("dev.engine_room.vanillin.config.SodiumVanillinConfig")
                     .getMethod("registerPage", ConfigBuilder.class, ModOptionsBuilder.class)
                     .invoke(null, builder, mod);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not register Vanillate settings", exception);
            }
        }
    }
}
