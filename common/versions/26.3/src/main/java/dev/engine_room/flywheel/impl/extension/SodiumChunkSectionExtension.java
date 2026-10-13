package dev.engine_room.flywheel.impl.extension;

import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;

/**
 * Terrain operations on Sodium's per-frame carrier. Producers run on the render thread with the host pass closed;
 * mode OFF only withdraws the CPU registry. The carrier records which layers are already drawn while renderGroup
 * still dispatches surrounding hooks.
 */
public interface SodiumChunkSectionExtension {
    void flywheel$prepareOpaque(VisualizationManagerImpl manager);

    void flywheel$prepareShadow(VisualizationManagerImpl manager);

    void flywheel$renderTranslucent(VisualizationManagerImpl manager, GpuSampler sampler);
}
