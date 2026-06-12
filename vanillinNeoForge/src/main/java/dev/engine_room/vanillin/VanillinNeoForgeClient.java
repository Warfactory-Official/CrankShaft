package dev.engine_room.vanillin;

import dev.engine_room.vanillin.item.ItemModels;
import dev.engine_room.vanillin.item.SodiumAnimatedTextureCompat;
import dev.engine_room.vanillin.visuals.ItemFrameVisual;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Vanillin.ID, dist = Dist.CLIENT)
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
                     .equals(Vanillin.ID)) {
                NeoForgeVanillinConfig.INSTANCE.apply();
            }
        });

        // Re-mark observed sprites active once per frame (same wiring as upstream's RenderFrameEvent.Pre).
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre stage) -> SodiumAnimatedTextureCompat.beginFrame());
        // Drop the observed-sprite set on resource reload so it doesn't hold stale (re-)baked sprites. Upstream
        // drives this from ReloadLevelRendererEvent, which this port omits (no such vanilla event on 26.2); the
        // client reload-listener hook fires at the same lifecycle points (F3+T, world load).
        modEventBus.addListener((AddClientReloadListenersEvent e) -> e.addListener(
                Identifier.fromNamespaceAndPath(Vanillin.ID, "sodium_animated_textures"),
                (PreparableReloadListener) (state, bgExec, barrier, reloadExec) ->
                        barrier.wait(null)
                               .thenRunAsync(() -> {
                                   SodiumAnimatedTextureCompat.onReloadRenderer();
                                   // Item models rebake on resource reload -- drop the stale item + frame bakes.
                                   ItemModels.clear();
                                   ItemFrameVisual.clearCache();
                               }, reloadExec)));
    }
}
