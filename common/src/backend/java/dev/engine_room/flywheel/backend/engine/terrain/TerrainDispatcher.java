package dev.engine_room.flywheel.backend.engine.terrain;

import dev.engine_room.flywheel.backend.engine.SodiumTerrainOitReplay;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

public interface TerrainDispatcher {
    boolean drawOpaqueSolid(ChunkRenderMatrices matrices, RenderSectionManager manager,
                            @Nullable Collection<RenderRegion> selfEnum);

    /**
     * Iris shadow-pass terrain; only the GL tier can host a shaderpack guest, so the Vulkan one never draws it.
     */
    default boolean drawShadowTerrain(ChunkRenderMatrices matrices, RenderSectionManager manager) {
        return false;
    }

    void prepareResidentTranslucent(ChunkRenderMatrices matrices, RenderSectionManager manager);

    void captureTranslucentArena(ChunkRenderMatrices matrices, RenderSectionManager manager);

    @Nullable
    SodiumTerrainOitReplay translucentOitReplay();

    void publishRegistry();

    void unpublishRegistry();

    void endFrame();

    void delete();
}
