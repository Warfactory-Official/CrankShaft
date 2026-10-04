package dev.engine_room.vanillin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;

@Mod(value = Vanillin.MOD_ID, dist = Dist.CLIENT)
public class VanillinNeoForgeClient {
    public VanillinNeoForgeClient(IEventBus modEventBus, ModContainer modContainer) {
        // VanillaVisuals.init() registers every STABLE visualizer into the Configurator; the config's apply()
        // (driven by the ModConfigEvent below, fired when the spec loads at startup) flushes the enabled ones into
        // the engine's VisualizerRegistry. NeoForgeVanillinConfig.INSTANCE must be touched only AFTER init() so its
        // constructor sees the populated Configurator. 26.2: the @Mod ctor takes (IEventBus, ModContainer) and the
        // config registers against the ModContainer directly (mirrors NeoForgeFlwConfig), not ModLoadingContext.
        VanillaVisuals.init();
        NeoForgeVanillinConfig.INSTANCE.registerSpecs(modContainer);

        modEventBus.<ModConfigEvent>addListener(event -> {
            if (event.getConfig()
                     .getModId()
                     .equals(Vanillin.MOD_ID)) {
                NeoForgeVanillinConfig.INSTANCE.apply();
            }
        });
    }
}
