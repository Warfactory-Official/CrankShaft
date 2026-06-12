package dev.engine_room.flywheel.backend.engine.terrain;

import org.lwjgl.vulkan.VkCommandBuffer;

public interface VkTerrainMeshDrawStrategy {
    void prepareEmit(VkTerrainDrawManager manager, int passIndex);

    void drawOpaque(VkTerrainDrawManager manager, int passIndex, VkCommandBuffer cmd);
}
