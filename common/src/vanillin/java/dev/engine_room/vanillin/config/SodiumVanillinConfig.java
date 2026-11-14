package dev.engine_room.vanillin.config;

import dev.engine_room.flywheel.impl.config.SodiumOptionTables;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.vanillin.VanillaVisuals;
import dev.engine_room.vanillin.VanillinXplat;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.Locale;

/**
 * Sodium-only client entrypoint; batch persistence precedes refreshing existing visuals.
 */
public final class SodiumVanillinConfig {
    private static final Identifier ENABLED = Identifier.parse("vanillate:enabled");
    private static final Identifier RELOAD = Identifier.parse("vanillate:reload_visuals");

    private static Component text(String name, Object... args) {
        return Component.translatable("vanillin.options." + name, args);
    }

    private SodiumVanillinConfig() {
    }

    /**
     * CrankShaft invokes this optional page contributor while registering its own Sodium options.
     */
    public static void registerPage(ConfigBuilder builder, ModOptionsBuilder mod) {
        var config = VanillinXplat.INSTANCE.getConfig();
        StorageEventHandler storage = config::saveAndApply;
        mod.registerFlagHook((_, _) -> VisualizationManagerImpl.resetAll(), RELOAD);
        var page = builder.createOptionPage().setName(text("page"))
                          .addOptionGroup(builder.createOptionGroup()
                                                 .addOption(builder.createBooleanOption(ENABLED)
                                                                   .setName(text("enabled"))
                                                                   .setTooltip(text("enabled.tooltip"))
                                                                   .setDefaultValue(true)
                                                                   .setImpact(OptionImpact.VARIES)
                                                                   .setStorageHandler(storage)
                                                                   .setBinding(config::setEnabled, config::enabled)
                                                                   .setFlags(RELOAD)));
        for (var blockEntity : new boolean[]{true, false}) {
            Collection<? extends Configurator.ConfiguredVisual> visuals = blockEntity
                    ? VanillaVisuals.CONFIGURATOR.blockEntities.values() : VanillaVisuals.CONFIGURATOR.entities.values();
            var keys = visuals.stream().map(Configurator.ConfiguredVisual::configKey)
                              .filter(key -> config.configurable(blockEntity, key)).sorted().toList();
            var category = blockEntity ? "block_entities" : "entities";
            var group = SodiumOptionTables.foldedGroup(builder, Identifier.fromNamespaceAndPath("vanillate", category),
                    text(category), text("fold.tooltip"), keys.size());
            for (var key : keys) {
                var nameKey = (blockEntity ? "block." : "entity.") + key.replace(':', '.');
                group.addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath("vanillate",
                                               category + "/" + key.replace(':', '/')), VisualConfigValue.class)
                                       .setName(Component.translatableWithFallback(nameKey, key))
                                       .setTooltip(value -> text(
                                               "visual." + value.name().toLowerCase(Locale.ROOT) + ".tooltip",
                                               Component.translatableWithFallback(nameKey, key)))
                                       .setElementNameProvider(
                                               value -> text("visual." + value.name().toLowerCase(Locale.ROOT)))
                                       .setDefaultValue(VisualConfigValue.DEFAULT)
                                       .setImpact(OptionImpact.VARIES)
                                       .setStorageHandler(storage)
                                       .setBinding(value -> config.setVisual(blockEntity, key, value),
                                               () -> config.visual(blockEntity, key))
                                       .setFlags(RELOAD));
            }
            page.addOptionGroup(group);
        }
        mod.addPage(page);
    }
}
