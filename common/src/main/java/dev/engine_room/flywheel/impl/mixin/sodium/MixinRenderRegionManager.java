package dev.engine_room.flywheel.impl.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainSectionListener;
import net.caffeinemc.mods.sodium.client.render.chunk.IntPool;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * GPU-resident terrain section registry hooks; render-thread only.
 */
@Mixin(value = RenderRegionManager.class, remap = false)
public class MixinRenderRegionManager {
    @Shadow
    @Final
    private IntPool freeIds;

    // Sodium 0.9.2: only fade-timed (first-build) uploads acquire a region id
    @Inject(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
            + "Ljava/util/Collection;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("HEAD"), require = 1)
    private void flywheel$acquireRegionId(RenderRegion region, Collection<BuilderTaskOutput> results,
                                          UniformBufferManager uniforms, CallbackInfo ci) {
        region.getOrAcquireId(freeIds);
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
        for (BuilderTaskOutput result : results) {
            listener.markSection(region, result.section.getSectionIndex());
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
        listener.onRegionFreed(region.getId());
    }
}
