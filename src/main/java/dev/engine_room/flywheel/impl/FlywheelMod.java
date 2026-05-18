package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.lib.compat.animation.SmartAnimatedTextureCompat;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.util.ResourceReloadHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.resource.ISelectiveResourceReloadListener;
import net.minecraftforge.client.resource.VanillaResourceType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.ICrashCallable;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(
        modid = Flywheel.ID,
        name = Tags.MODNAME,
        version = Tags.VERSION,
        clientSideOnly = true,
        acceptableRemoteVersions = "*"
)
public final class FlywheelMod {

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        FlwConfig.INSTANCE.load(event.getModConfigurationDirectory());
        // 1.12.2: class-load Backends so INDIRECT/INSTANCING are in Backend.REGISTRY before the
        // resource reload listener fires its first chooseBackend() pass.
        BackendManagerImpl.init();
        registerShaderReload();
        registerCrashCallable();
        MinecraftForge.EVENT_BUS.register(FlwEvents.INSTANCE);
        ClientCommandHandler.instance.registerCommand(new CommandFlywheel());
        SmartAnimatedTextureCompat.register();
        FlwImpl.LOGGER.info("CrankShaft loaded");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        FlwImpl.freezeRegistries();
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
    }

    private static void registerCrashCallable() {
        FMLCommonHandler.instance().registerCrashCallable(new ICrashCallable() {
            @Override
            public String getLabel() {
                return "Flywheel Backend";
            }

            @Override
            public String call() {
                return BackendManagerImpl.getBackendString();
            }
        });
    }

    private static void registerShaderReload() {
        IReloadableResourceManager mgr = (IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager();
        // 1.12.2: registerReloadListener fires the listener immediately, so this also bootstraps.
        mgr.registerReloadListener((ISelectiveResourceReloadListener) (m, predicate) -> {
            if (!predicate.test(VanillaResourceType.SHADERS)) return;
            FlwPrograms.reload(m);
            RendererReloadCache.onReloadLevelRenderer();
            ResourceReloadHolder.onEndClientResourceReload();
            BackendManagerImpl.onEndClientResourceReload(false);
        });
    }
}
