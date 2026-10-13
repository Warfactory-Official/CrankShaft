package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
public class MixinChunkSectionsToRender {
    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true, require = 1)
    private void flywheel$routeTranslucentToOit(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (group != ChunkSectionLayerGroup.TRANSLUCENT) {
            return;
        }
        LodCompat.captureTerrainDepth(1);
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager == null) {
            return;
        }
        if (manager.renderTranslucentOit((ChunkSectionsToRender) (Object) this, sampler)) {
            LodCompat.captureTerrainDepth(2);
            ci.cancel();
        }
    }

    @Inject(method = "renderGroup", at = @At("RETURN"), require = 1)
    private void flywheel$captureTranslucentTerrain(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (group == ChunkSectionLayerGroup.TRANSLUCENT) {
            LodCompat.captureTerrainDepth(2);
        }
    }
}
