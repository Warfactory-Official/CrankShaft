package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
import dev.engine_room.flywheel.iris.compile.FarLayerIrisBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replays native shaderpack water and retained layers before resolving their shared pack attachments.
 */
@Mixin(LodRenderer.class)
abstract class FarDhWaterMixin {
    @WrapOperation(method = "renderTerrain(Lcom/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhTerrainRenderer;Lcom/seibel/distanthorizons/core/render/RenderBufferHandler;Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V",
            at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhTerrainRenderer;render(Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/util/objects/SortedArraySet;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V"))
    private void flywheel$waterGraph(IDhTerrainRenderer renderer, RenderParams params, boolean opaque,
                                     SortedArraySet<LodBufferContainer> buffers, IProfilerWrapper profiler,
                                     Operation<Void> original) {
        if (!opaque && FarLayerIrisBridge.dhWater(params,
                () -> original.call(renderer, params, false, buffers, profiler))) return;
        original.call(renderer, params, opaque, buffers, profiler);
    }
}
