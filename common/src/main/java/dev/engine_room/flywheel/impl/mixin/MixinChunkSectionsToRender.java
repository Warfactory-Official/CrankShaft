package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
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
    private void flw$routeTranslucentToOit(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
        if (group != ChunkSectionLayerGroup.TRANSLUCENT) {
            return;
        }
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager == null) {
            return;
        }
        if (manager.renderTranslucentOit((ChunkSectionsToRender) (Object) this, sampler)) {
            ci.cancel();
        }
    }
}
