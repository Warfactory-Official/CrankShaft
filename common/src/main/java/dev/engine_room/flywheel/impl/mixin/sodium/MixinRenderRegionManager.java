package dev.engine_room.flywheel.impl.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainSectionListener;
import dev.engine_room.flywheel.impl.sodium.SodiumRegionFeed;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
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

        int geometryHandle = SodiumRegionFeed.geometryHandle(region);
        if (geometryHandle != listener.cachedGeometryHandle(regionId)) {
            SodiumRegionFeed.feedRegion(listener, region, geometryHandle);
            return;
        }

        for (BuilderTaskOutput result : results) {
            SodiumRegionFeed.feedSection(listener, region, result.section.getSectionIndex(), geometryHandle);
        }
    }

    @Inject(method = "update(Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
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
