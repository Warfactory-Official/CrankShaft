package dev.engine_room.flywheel.impl.mixin.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.engine_room.flywheel.impl.compat.SodiumCompat;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Engine's render-time seam into Sodium's terrain draw: OPAQUE -> MDI solid+cutout, TRANSLUCENT -> OIT replay.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer {
    @Shadow
    @Nullable
    private ClientLevel level;

    @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true, require = 1)
    private void flywheel$routeTerrainToEngine(ChunkSectionLayerGroup group, ChunkRenderMatrices matrices,
                                               double x, double y, double z, GpuSampler terrainSampler,
                                               CallbackInfo ci) {
        if (!SodiumCompat.isSodiumActive()) {
            return;
        }
        var manager = VisualizationManagerImpl.get(this.level);
        if (manager == null) {
            return;
        }

        if (group == ChunkSectionLayerGroup.OPAQUE) {
            var sectionManager = ((SodiumWorldRendererAccessor) (Object) this).flywheel$getRenderSectionManager();
            if (sectionManager == null) {
                return;
            }
            if (manager.renderOpaqueSolidTerrain(matrices, sectionManager)) {
                ci.cancel();
            }
            return;
        }

        if (group != ChunkSectionLayerGroup.TRANSLUCENT) {
            return;
        }

        if (manager.renderTranslucentOitSodium((SodiumWorldRenderer) (Object) this, matrices, x, y, z,
                terrainSampler)) {
            ci.cancel();
        }
    }
}
