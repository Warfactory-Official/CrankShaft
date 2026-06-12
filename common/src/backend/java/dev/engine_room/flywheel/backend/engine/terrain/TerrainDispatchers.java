package dev.engine_room.flywheel.backend.engine.terrain;

import dev.engine_room.flywheel.backend.vk.VkContext;

public final class TerrainDispatchers {
    private TerrainDispatchers() {
    }

    public static boolean isSupported() {
        return VkContext.isVulkanHost() ? VkTerrainDrawManager.isSupported() : TerrainDrawDispatcher.isSupported();
    }

    public static void logUnsupportedOnce() {
        if (VkContext.isVulkanHost()) {
            VkTerrainDrawManager.logUnsupportedOnce();
        } else {
            TerrainDrawDispatcher.logUnsupportedOnce();
        }
    }

    public static TerrainDispatcher create() {
        return VkContext.isVulkanHost() ? new VkTerrainDrawManager() : new TerrainDrawDispatcher();
    }

    public static void disableAfterInitFailure(RuntimeException e) {
        if (VkContext.isVulkanHost()) {
            VkTerrainDrawManager.disableAfterInitFailure(e);
        } else {
            TerrainDrawDispatcher.disableAfterInitFailure(e);
        }
    }
}
