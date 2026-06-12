package dev.engine_room.vanillin;

import dev.engine_room.vanillin.item.ItemModels;
import dev.engine_room.vanillin.item.SodiumAnimatedTextureCompat;
import dev.engine_room.vanillin.visuals.ItemFrameVisual;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

public class VanillinFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // VanillaVisuals.init() registers the visualizers into the Configurator; apply(CONFIGURATOR) flushes the
        // enabled ones into the engine's VisualizerRegistry (load/save persist the per-visual enable/disable file).
        VanillaVisuals.init();
        FabricVanillinConfig.INSTANCE.load();
        FabricVanillinConfig.INSTANCE.apply(VanillaVisuals.CONFIGURATOR);
        FabricVanillinConfig.INSTANCE.save();

        // 26.2 replaced WorldRenderEvents.START with LevelRenderEvents.START_MAIN (fires once at the start of the
        // main pass each frame). Counterpart of upstream's WorldRenderEvents.START wiring for beginFrame().
        LevelRenderEvents.START_MAIN.register(context -> SodiumAnimatedTextureCompat.beginFrame());
        // Drop the observed-sprite set on resource reload so it doesn't hold stale (re-)baked sprites. Upstream
        // drives this from ReloadLevelRendererCallback, which this port omits (no such vanilla event on 26.2); the
        // resource-reload hook fires at the same lifecycle points (F3+T, world load).
        ResourceLoader.get(PackType.CLIENT_RESOURCES)
                      .registerReloadListener(Vanillin.rl("sodium_animated_textures"),
                              (PreparableReloadListener) (state, bgExec, barrier, reloadExec) ->
                                      barrier.wait(null)
                                             .thenRunAsync(() -> {
                                                 SodiumAnimatedTextureCompat.onReloadRenderer();
                                                 // Item models rebake on resource reload -- drop the stale item + frame bakes.
                                                 ItemModels.clear();
                                                 ItemFrameVisual.clearCache();
                                             }, reloadExec));
    }
}
