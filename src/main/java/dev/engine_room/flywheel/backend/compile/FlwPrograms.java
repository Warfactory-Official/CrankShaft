package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.backend.NoiseTextures;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public final class FlwPrograms {
    public static final Logger LOGGER = LogManager.getLogger(Flywheel.ID + "/backend/shaders");

    private static final ResourceLocation COMPONENTS_HEADER_FRAG = ResourceUtil.rl("internal/components_header.frag");

    public static ShaderSources SOURCES;

    private FlwPrograms() {
    }

    public static void reload(IResourceManager manager) {
        // Reset the programs in case the ubershader load fails.
        InstancingPrograms.setInstance(null);
        IndirectPrograms.setInstance(null);

        var sources = new ShaderSources(manager);
        SOURCES = sources;

        var fragmentComponentsHeader = sources.get(COMPONENTS_HEADER_FRAG);

        List<SourceComponent> vertexComponents = List.of();
        List<SourceComponent> fragmentComponents = List.of(fragmentComponentsHeader);

        InstancingPrograms.reload(sources, vertexComponents, fragmentComponents);
        IndirectPrograms.reload(sources, vertexComponents, fragmentComponents);

        NoiseTextures.reload(manager);
    }
}
