package dev.engine_room.flywheel.backend.engine.terrain;

import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.vk.VkContext;

import java.util.Objects;

public final class TerrainDispatchers {
    private TerrainDispatchers() {
    }

    public static boolean isSupported() {
        return VkContext.isVulkanHost() ? VkPrograms.allLoaded() : TerrainDrawDispatcher.isSupported();
    }

    public static void logUnsupportedOnce() {
        if (VkContext.isVulkanHost()) {
            VkTerrainDrawManager.logUnsupportedOnce();
        } else {
            TerrainDrawDispatcher.logUnsupportedOnce();
        }
    }

    public static TerrainDispatcher create() {
        if (!VkContext.isVulkanHost()) {
            return new TerrainDrawDispatcher();
        }
        return VkTerrainDrawManager.isSupported() ? new VkTerrainDrawManager()
                : new VkTerrainClassicDrawManager(Objects.requireNonNull(VkPrograms.get()));
    }

    public static void disableAfterInitFailure(RuntimeException e) {
        if (VkContext.isVulkanHost()) {
            VkTerrainDrawManager.disableAfterInitFailure(e);
        } else {
            TerrainDrawDispatcher.disableAfterInitFailure(e);
        }
    }
}
