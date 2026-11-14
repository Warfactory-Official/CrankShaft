package dev.engine_room.flywheel.impl.config;

import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.IntegerOption;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;

/**
 * Descriptions and optional captured images consumed by Sodium's stock tooltip.
 */
public final class SodiumOptionTooltips {
    public static final int SCENE_WIDTH = 960;
    public static final int SCENE_HEIGHT = 540;
    public static final int TEXTURE_HEIGHT = 728;
    private static final Identifier OIT = SodiumConfig.id("oit");
    private static final Identifier TERRAIN = SodiumConfig.id("terrain");
    private static final Identifier EXACT = texture("exact");
    private static final Identifier WAVELET = texture("oit_wavelet");
    private static final Identifier[] TERRAIN_TEXTURES = Arrays.stream(TerrainMode.values())
                                                               .map(mode -> mode == TerrainMode.FULL ? EXACT : texture(
                                                                       "terrain_" + lower(mode)))
                                                               .toArray(Identifier[]::new);
    private static final Identifier[] LAYER_IDS = Arrays.stream(OitConfig.Path.values())
                                                        .map(path -> SodiumConfig.id("layers." + lower(path)))
                                                        .toArray(Identifier[]::new);

    private SodiumOptionTooltips() {
    }

    public static Component terrainDescription(TerrainMode mode) {
        return SodiumConfig.text("preview.terrain." + lower(mode));
    }

    public static Component oitDescription(OitConfig.Path requested) {
        var resolved = OitConfig.resolvePath(requested);
        var key = "preview.oit." + lower(resolved);
        var description = resolved == OitConfig.Path.WAVELET ? SodiumConfig.text(key)
                : SodiumConfig.text(key, layers(resolved));
        if (requested == OitConfig.Path.AUTO || requested == resolved) return description;
        return SodiumConfig.text("preview.fallback").append(" ").append(description);
    }

    /**
     * Uses the staged option value; showing an image never applies a setting.
     */
    public static @Nullable Identifier previewTexture(Option option) {
        return previewTexture(option, null);
    }

    public static @Nullable Identifier previewTexture(Option option, @Nullable Object candidate) {
        var id = SodiumOptionTables.optionId(option);
        if (!id.equals(TERRAIN) && !id.equals(OIT)) return null;
        var value = candidate != null ? candidate : ((StatefulOption<?>) option).getValidatedValue();
        if (id.equals(TERRAIN)) {
            var mode = (TerrainMode) value;
            return TERRAIN_TEXTURES[mode.ordinal()];
        }
        var path = (OitConfig.Path) value;
        return OitConfig.resolvePath(path) == OitConfig.Path.WAVELET ? WAVELET : EXACT;
    }

    @SuppressWarnings("unchecked")
    public static Component candidateDescription(Option option, Object value) {
        return ((StatefulOption<Object>) option).getTooltipProvider().apply(value);
    }

    private static int layers(OitConfig.Path path) {
        var option = (IntegerOption) ConfigManager.CONFIG.getOption(LAYER_IDS[path.ordinal()]);
        var staged = option.getValidatedValue();
        return staged == 0 ? OitConfig.preset(path) : staged;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath("flywheel", "textures/gui/settings/" + name + ".png");
    }
}
