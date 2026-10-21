package dev.engine_room.flywheel.backend.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import dev.engine_room.flywheel.backend.engine.NativeChunkOit;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = RenderGlobal.class, priority = 900)
public abstract class MixinRenderLayerCompletion {
    @Shadow private WorldClient world;

    // An outer wrapper also completes layers cancelled by replacement renderers' HEAD injections.
    @WrapMethod(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I")
    private int flw$completeLayer(BlockRenderLayer layer, double partialTicks, int pass, Entity entity,
                                   Operation<Integer> original) {
        if (layer == BlockRenderLayer.TRANSLUCENT && NativeChunkOit.needed()) {
            try (var replay = NativeChunkOit.begin(() -> original.call(layer, partialTicks, pass, entity))) {
                VisualizationHelper.dispatchAfterTranslucent(world);
                return replay.count();
            }
        }
        var count = original.call(layer, partialTicks, pass, entity);
        if (layer == BlockRenderLayer.TRANSLUCENT) VisualizationHelper.dispatchAfterTranslucent(world);
        return count;
    }
}
