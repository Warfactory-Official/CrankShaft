package dev.engine_room.flywheel.impl.sodium;

import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDebug;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainSectionListener;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.impl.BackendManagerImpl;

/**
 * Predicate {@code MixinRenderSectionManager} evaluates at the EXTRACT seam to cancel Sodium's cull.
 */
public final class TerrainCullGate {
    private TerrainCullGate() {
    }

    public static boolean shouldCancelSodiumCull() {
        return TerrainDebug.SODIUM_CULL
                && !VkContext.isVulkanHost()
                && BackendConfig.INSTANCE.terrainMode() == TerrainMode.FULL
                && BackendManagerImpl.isGpuDriven()
                && TerrainSectionListener.published() != null;
    }
}
