package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;

/**
 * Pluggable translucent-terrain draw strategy invoked at the OIT producer seam INSTEAD of CrankShaft's own MDI,
 * when a GPU-driven mesh backend is active and a strategy has been registered. The mesh tier is a PEER PRODUCER
 * into the existing OIT chain (not back-to-front sorted).
 */
public interface TerrainTranslucentMeshDrawStrategy {
    void prepareCommands(TerrainDrawDispatcher dispatcher);

    void draw(TerrainDrawDispatcher dispatcher, RenderPass pass, OitMode mode, OitFramebuffer framebuffer,
              boolean fading, GpuTextureView lightmapView, GpuTextureView blueNoiseView,
              GpuSampler clampLinear, GpuSampler oitSampler, GpuSampler noiseSampler);

    void drawInsert(TerrainDrawDispatcher dispatcher, RenderPass pass, OitInsertMode mode, boolean fading,
                    GpuTextureView lightmapView, GpuSampler clampLinear);
}
