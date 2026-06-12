package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.util.ResourceReloadHolder;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FlwPrograms {
    public static final Logger LOGGER = LogManager.getLogger(Flywheel.ID + "/backend/shaders");

    public static ShaderSources SOURCES;

    private FlwPrograms() {
    }

    public static void reload(ResourceManager manager) {
        Models.invalidate();
        RendererReloadCache.onReloadLevelRenderer();
        ResourceReloadHolder.onEndClientResourceReload();
        var sources = new ShaderSources(manager);
        SOURCES = sources;

        if (VkContext.isVulkanHost()) {
            // Vulkan host: publish the VK program set. GlCompat MUST NOT be referenced -- no GL context on a Vulkan host.
            VkPrograms.reload(sources);
        } else {
            InstancingPrograms.setInstance(null);
            IndirectPrograms.setInstance(null);
            InstancingPrograms.reload();
            IndirectPrograms.reload(sources);
        }

        NoiseTextures.reload(manager);
        ShaderWarmup.warm();
    }
}
