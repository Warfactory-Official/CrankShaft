package dev.engine_room.flywheel.impl.sodium;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainSectionListener;
import dev.engine_room.flywheel.backend.vk.VkContext;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.jspecify.annotations.Nullable;

/**
 * Copies Sodium's per-section mesh records into the published {@link TerrainSectionListener}; render-thread only.
 */
public final class SodiumRegionFeed {
    private SodiumRegionFeed() {
    }

    public static int geometryHandle(RenderRegion region) {
        var resources = region.getResources();
        return gpuBufferHandle(resources == null ? null : resources.getGeometryBuffer());
    }

    public static void feedRegion(TerrainSectionListener listener, RenderRegion region, int geometryHandle) {
        int regionId = region.getId();
        listener.noteRegionIdentity(regionId, region.getChunkX(), region.getChunkY(), region.getChunkZ(),
                geometryHandle);
        for (int s = 0; s < RenderRegion.REGION_SIZE; s++) {
            feedSection(listener, region, s, geometryHandle);
        }
    }

    public static void feedSection(TerrainSectionListener listener, RenderRegion region, int s, int geometryHandle) {
        listener.onSectionMeshed(region.getId(), region.getChunkX(), region.getChunkY(), region.getChunkZ(), s,
                dataPointer(region, DefaultTerrainRenderPasses.SOLID, s),
                dataPointer(region, DefaultTerrainRenderPasses.CUTOUT, s),
                dataPointer(region, DefaultTerrainRenderPasses.TRANSLUCENT, s),
                geometryHandle);
    }

    private static long dataPointer(RenderRegion region, TerrainRenderPass pass, int s) {
        var storage = region.getStorage(pass);
        return storage == null ? 0L : storage.getDataPointer(s);
    }

    private static int gpuBufferHandle(@Nullable GpuBuffer buffer) {
        if (buffer == null || buffer.isClosed()) {
            return -1;
        }
        if (VkContext.isVulkanHost()) {
            return buffer instanceof VulkanGpuBuffer vkBuffer ? (int) vkBuffer.vkBuffer() : -1;
        }
        return buffer instanceof GlBuffer glBuffer ? glBuffer.handle() : -1;
    }
}
