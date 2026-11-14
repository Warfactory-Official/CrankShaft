package dev.engine_room.flywheel.impl.config;

import com.google.common.collect.ImmutableList;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.impl.BackendManagerImpl;
import dev.engine_room.flywheel.impl.compat.NvidiumCompat;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.BooleanOption;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.config.structure.OptionGroup;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.config.structure.Page;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.minecraft.resources.Identifier;

/**
 * Client-thread applicability captured before laying out a Sodium settings screen. Saved values are retained.
 */
public final class SodiumOptionVisibility {
    private static final Identifier BACKEND = SodiumConfig.id("backend");
    private static final Identifier TERRAIN = SodiumConfig.id("terrain");
    private static final Identifier OIT = SodiumConfig.id("oit");
    private static final Identifier COMMON_POOL = SodiumConfig.id("common_pool");
    private static final Identifier VANILLATE_ENABLED = Identifier.parse("vanillate:enabled");
    private static State current;

    private SodiumOptionVisibility() {
    }

    public record State(Backend backend, Backend active, TerrainMode terrain, OitConfig.Path oit,
                        boolean commonPool, boolean vanillaVisuals, boolean packOit, boolean nvidiumOwned) {
        public boolean off() {
            return backend == BackendManagerImpl.OFF_BACKEND;
        }

        public boolean iris() {
            var id = Backend.REGISTRY.getIdOrThrow(backend);
            return id.getNamespace().equals("flywheel") && id.getPath().startsWith("iris_");
        }
    }

    public static State capture() {
        var vanilla = ConfigManager.CONFIG.getOption(VANILLATE_ENABLED);
        return new State(SodiumDropdowns.selectedBackend(), BackendManagerImpl.currentBackend(),
                (TerrainMode) value(TERRAIN), OitConfig.resolvePath((OitConfig.Path) value(OIT)),
                (boolean) value(COMMON_POOL), vanilla instanceof BooleanOption option && option.getValidatedValue(),
                OitConfig.shaderpackOitAvailable(), NvidiumCompat.engineOwnsTerrain());
    }

    public static void install(State state) {
        current = state;
    }

    public static boolean isVisible(Option option) {
        var id = SodiumOptionTables.optionId(option);
        if (id.equals(BACKEND)) return true;
        if (NvidiumCompat.isNvidiumOption(id) && current.nvidiumOwned()) return false;
        if (id.getNamespace().equals("vanillate"))
            return !current.off() && (id.equals(VANILLATE_ENABLED) || current.vanillaVisuals());
        if (!id.getNamespace().equals("crankshaft")) return true;
        if (current.off()) return false;
        return switch (id.getPath()) {
            case "shaderpack_oit" -> current.packOit();
            case "oit", "exact_weather" -> !current.iris();
            case "layers.kbuffer" -> !current.iris() && current.oit() == OitConfig.Path.KBUFFER;
            case "layers.mlab" -> !current.iris() && current.oit() == OitConfig.Path.MLAB;
            case "layers.abuffer" -> !current.iris() && current.oit() == OitConfig.Path.ABUFFER;
            case "worker_threads" -> !current.commonPool();
            default -> true;
        };
    }

    public static boolean valueAvailable(Option option, Object value) {
        var id = SodiumOptionTables.optionId(option);
        if (id.equals(OIT)) {
            var requested = (OitConfig.Path) value;
            return requested == OitConfig.Path.AUTO || OitConfig.resolvePath(requested) == requested;
        }
        return !id.equals(TERRAIN) || !((TerrainMode) value).ownsOpaque() || current.backend().isGpuDriven();
    }

    public static Object effectiveValue(Option option, Object value) {
        var id = SodiumOptionTables.optionId(option);
        if (id.equals(BACKEND)) return 0;
        if (id.equals(OIT)) return OitConfig.resolvePath((OitConfig.Path) value);
        var terrain = (TerrainMode) value;
        return terrain == TerrainMode.OPAQUE ? TerrainMode.OFF : TerrainMode.TRANSLUCENT_OIT;
    }

    public static ImmutableList<Page> pages(ImmutableList<Page> pages) {
        return pages.stream().filter(page -> !(page instanceof OptionPage)
                            || page.groups().stream().anyMatch(SodiumOptionVisibility::hasOptions))
                    .collect(ImmutableList.toImmutableList());
    }

    public static ImmutableList<OptionGroup> groups(ImmutableList<OptionGroup> groups) {
        return groups.stream().filter(SodiumOptionVisibility::hasOptions).collect(ImmutableList.toImmutableList());
    }

    private static boolean hasOptions(OptionGroup group) {
        return group.options().stream().anyMatch(SodiumOptionVisibility::isVisible);
    }

    private static Object value(Identifier id) {
        return ((StatefulOption<?>) ConfigManager.CONFIG.getOption(id)).getValidatedValue();
    }
}
