package dev.engine_room.flywheel.impl.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainSectionListener;
import dev.engine_room.flywheel.backend.vk.VkContext;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * GPU-resident terrain section registry hooks; render-thread only.
 */
@Mixin(value = RenderRegionManager.class, remap = false)
public class MixinRenderRegionManager {
    private static void feedSection(TerrainSectionListener listener, RenderRegion region, int regionId,
                                    int originX, int originY, int originZ, int s, int geometryHandle) {
        long dataPtrSolid = dataPointer(region,
                DefaultTerrainRenderPasses.SOLID, s);
        long dataPtrCutout = dataPointer(region,
                DefaultTerrainRenderPasses.CUTOUT, s);
        long dataPtrTranslucent = dataPointer(region,
                DefaultTerrainRenderPasses.TRANSLUCENT, s);
        listener.onSectionMeshed(regionId, originX, originY, originZ, s,
                dataPtrSolid, dataPtrCutout, dataPtrTranslucent, geometryHandle);
    }

    private static long dataPointer(RenderRegion region,
                                    TerrainRenderPass pass,
                                    int s) {
        var storage = region.getStorage(pass);
        return storage == null ? 0L : storage.getDataPointer(s);
    }

    private static @Nullable GpuBuffer geometryBuffer(RenderRegion region) {
        var resources = region.getResources();
        return resources == null ? null : resources.getGeometryBuffer();
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

    @Inject(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
            + "Ljava/util/Collection;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("RETURN"), require = 1)
    private void flywheel$onMeshApplied(RenderRegion region, Collection<BuilderTaskOutput> results,
                                        UniformBufferManager uniforms, CallbackInfo ci) {
        TerrainSectionListener listener = TerrainSectionListener.published();
        if (listener == null) {
            return;
        }
        int regionId = region.getId();
        if (regionId == -1) {
            return;
        }

        int originX = region.getChunkX();
        int originY = region.getChunkY();
        int originZ = region.getChunkZ();

        GpuBuffer geometry = geometryBuffer(region);
        int geometryHandle = gpuBufferHandle(geometry);
        boolean handleChanged = geometryHandle != listener.cachedGeometryHandle(regionId);

        if (handleChanged) {
            listener.noteRegionIdentity(regionId, originX, originY, originZ, geometryHandle);
            for (int s = 0; s < RenderRegion.REGION_SIZE; s++) {
                feedSection(listener, region, regionId, originX, originY, originZ, s, geometryHandle);
            }
            return;
        }

        for (BuilderTaskOutput result : results) {
            int s = result.section.getSectionIndex();
            feedSection(listener, region, regionId, originX, originY, originZ, s, geometryHandle);
        }
    }

    @Inject(method = "update()V",
            at = @At(value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/IntPool;release(I)V"),
            require = 1)
    private void flywheel$onRegionFreed(CallbackInfo ci, @Local RenderRegion region) {
        TerrainSectionListener listener = TerrainSectionListener.attached();
        if (listener == null) {
            return;
        }
        int regionId = region.getId();
        if (regionId != -1) {
            listener.onRegionFreed(regionId);
        }
    }
}
