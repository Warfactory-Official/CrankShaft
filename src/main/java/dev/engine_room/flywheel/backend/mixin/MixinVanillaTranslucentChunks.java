package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderGlobal.class)
public abstract class MixinVanillaTranslucentChunks {
    @Shadow private WorldClient world;

    // This config is queued only when the vanilla draw helper survives renderer selection.
    @Redirect(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderGlobal;renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;)V"),
            require = 1)
    private void flw$deferChunks(RenderGlobal renderer, BlockRenderLayer layer) {
        if (layer == BlockRenderLayer.TRANSLUCENT && FlwPrograms.chunkOitPrograms() != null
                && VisualizationManager.supportsVisualization(world)) return;
        renderer.renderBlockLayer(layer);
    }
}
