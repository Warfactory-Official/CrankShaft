package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.backend.engine.shaderpack.GuestRenderPass;
import dev.engine_room.flywheel.backend.engine.shaderpack.NothiriumTerrain;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.PipelineFrameLifecycle1", remap = false)
public abstract class NothiriumTerrainLayerMixin {
    // The native Nothirium bridge returns without calling RenderGlobal's layer-completion hook.
    @WrapMethod(method = "renderWorldBlockLayer")
    private int flw$complete(RenderGlobal renderer, BlockRenderLayer layer, double partialTicks, int pass, Entity entity,
                             Operation<Integer> original) {
        if (layer != BlockRenderLayer.TRANSLUCENT || GuestRenderPass.shadowActive() || !NothiriumTerrain.enabled())
            return original.call(renderer, layer, partialTicks, pass, entity);
        try (var scope = NothiriumTerrain.begin()) {
            var count = original.call(renderer, layer, partialTicks, pass, entity);
            if (scope.captured()) VisualizationHelper.dispatchAfterTranslucent(Minecraft.getMinecraft().world);
            return count;
        }
    }
}
