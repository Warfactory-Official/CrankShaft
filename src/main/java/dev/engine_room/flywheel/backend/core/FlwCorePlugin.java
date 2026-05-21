package dev.engine_room.flywheel.backend.core;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.service.mojang.MixinServiceLaunchWrapper;
import zone.rong.mixinbooter.Context;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.io.File;
import java.util.List;

@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({
        "dev.engine_room.flywheel.backend.core",
        "dev.engine_room.flywheel.backend.mixin"
})
@IFMLLoadingPlugin.Name("CrankShaft")
@SuppressWarnings("deprecation") // Cleanroom shaded IEarlyMixinLoader
public class FlwCorePlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {

    static {
        Launch.classLoader.addTransformerExclusion("dev.engine_room.flywheel.backend.core.");
        Launch.classLoader.addTransformerExclusion("dev.engine_room.flywheel.backend.mixin.");
        MixinServiceLaunchWrapper.registerMixinClassTransformer(
                new SodiumRenderGlobalTransformer(),
                "me.jellysquid.mods.sodium.mixin.features.chunk_rendering.MixinRenderGlobal");
        MixinServiceLaunchWrapper.registerMixinClassTransformer(
                new SodiumRenderGlobalTransformer(),
                "org.embeddedt.vintagefix.mixin.bugfix.entity_disappearing.MixinRenderGlobal");
        MixinServiceLaunchWrapper.registerMixinClassTransformer(
                new CeleritasRenderGlobalTransformer(),
                "org.taumc.celeritas.mixin.core.terrain.RenderGlobalMixin");
        MixinServiceLaunchWrapper.registerMixinClassTransformer(
                new RenderLibBoundingBoxCacheTransformer(),
                "meldexun.renderlib.mixin.caching.boundingbox.MixinEntityRenderer");
    }

    @Override
    public List<String> getMixinConfigs() {
        return List.of(
                "flywheel.mixin.json",
                "flywheel.nooptifine.mixin.json",
                "flywheel.datamanager.mixin.json"
        );
    }

    @Override
    public boolean shouldMixinConfigQueue(Context context) {
        return switch (context.mixinConfig()) {
            case "flywheel.nooptifine.mixin.json" -> !context.isModPresent("optifine");
            case "flywheel.datamanager.mixin.json" -> dataManagerRewriteEnabled();
            default -> true;
        };
    }

    // FlwConfig loads in preInit, long after mixin queueing, so this key is owned here and read
    // LoliASM-style: forge Configuration works fine at coremod time, and get-or-create materializes
    // the key on first launch. FlwConfig round-trips it untouched.
    private static boolean dataManagerRewriteEnabled() {
        Configuration cfg = new Configuration(new File(Launch.minecraftHome, "config/flywheel.cfg"));
        boolean enabled = cfg.getBoolean("dataManagerRewrite", "client", true,
                "Replace vanilla EntityDataManager's locked HashMap storage with a lock-free flat array. "
                        + "Synced entity flag reads (isInvisible/isChild/...) dominate the render path at high "
                        + "entity counts. Requires a game restart to take effect.");
        if (cfg.hasChanged()) {
            cfg.save();
        }
        return enabled;
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{"dev.engine_room.flywheel.backend.core.FlwCoreTransformer"};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
