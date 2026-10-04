package dev.engine_room.vanillin;

import net.fabricmc.api.ClientModInitializer;

public class VanillinFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // VanillaVisuals.init() registers the visualizers into the Configurator; apply(CONFIGURATOR) flushes the
        // enabled ones into the engine's VisualizerRegistry (load/save persist the per-visual enable/disable file).
        VanillaVisuals.init();
        FabricVanillinConfig.INSTANCE.load();
        FabricVanillinConfig.INSTANCE.apply(VanillaVisuals.CONFIGURATOR);
        FabricVanillinConfig.INSTANCE.save();
    }
}
