package dev.engine_room.flywheel.backend.core;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.service.mojang.MixinServiceLaunchWrapper;
import zone.rong.mixinbooter.Context;
import zone.rong.mixinbooter.IEarlyMixinLoader;

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
                "flywheel.nooptifine.mixin.json"
        );
    }

    @Override
    public boolean shouldMixinConfigQueue(Context context) {
        return switch (context.mixinConfig()) {
            case "flywheel.nooptifine.mixin.json" -> !context.isModPresent("optifine");
            default -> true;
        };
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
