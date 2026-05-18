package dev.engine_room.vanillin;

import dev.engine_room.flywheel.Tags;
import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.vanillin.visuals.BellVisual;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import thedarkcolour.futuremc.tile.BellTileEntity;

@Mod(
        modid = Tags.VANILLIN_ID,
        name = Tags.VANILLIN_NAME,
        version = Tags.VANILLIN_VERSION,
        clientSideOnly = true,
        acceptableRemoteVersions = "*",
        dependencies = "required-after:" + Flywheel.ID
)
public final class Vanillin {

    public static final Logger LOGGER = LogManager.getLogger(Tags.VANILLIN_NAME);
    public static final Logger CONFIG_LOGGER = LogManager.getLogger(Tags.VANILLIN_NAME + "/config");

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        VanillaVisuals.init();
        VanillinFlwConfig.INSTANCE.load(event.getModConfigurationDirectory().toPath());
        LOGGER.info("Vanillate loaded");
    }

    // Future-MC registers BellTileEntity in its RegistryEvent.Register<Block> handler, which
    // fires after preInit — so Configurator.configKey() (which reads the TE static registry)
    // can't resolve it until init. Defer apply() so late registrations are wired in one pass.
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (Loader.isModLoaded("futuremc")) {
            VanillaVisuals.blockEntity(BellTileEntity.class)
                    .factory(BellVisual::new)
                    .apply(VanillaVisuals.STABLE);
        }
        VanillinFlwConfig.INSTANCE.apply(VanillaVisuals.CONFIGURATOR);
        VanillinFlwConfig.INSTANCE.save();
    }
}
