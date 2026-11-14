package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitPass;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.impl.compat.NativeLodWater;
import dev.engine_room.flywheel.impl.compat.NativeWaterRenderer;
import dev.engine_room.flywheel.impl.compat.NativeWaterReplay;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LodRenderer.class)
abstract class DhLodWaterMixin {
    // The shared terrain seam preserves both native engines' buffer selection and precedes fog/TAA/final blit.
    @WrapOperation(method = "renderTerrain(Lcom/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhTerrainRenderer;Lcom/seibel/distanthorizons/core/render/RenderBufferHandler;Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V",
            at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhTerrainRenderer;render(Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/util/objects/SortedArraySet;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V"))
    private void flywheel$water(IDhTerrainRenderer renderer, RenderParams params, boolean opaque,
                                SortedArraySet<LodBufferContainer> buffers, IProfilerWrapper profiler,
                                Operation<Void> original) {
        if (!ShadersModHelper.isShaderPackInUse() && renderer instanceof NativeWaterRenderer nativeWater) {
            NativeLodWater.observeDh(nativeWater);
        }
        if (!opaque && !ShadersModHelper.isShaderPackInUse() && renderer instanceof NativeWaterRenderer nativeWater) {
            boolean drawn = NativeLodWater.render(DistantHorizonsCompat.farHost(params.apiCopy),
                    new NativeWaterReplay() {
                        @Override
                        public boolean ready() {
                            return nativeWater.flywheel$waterReady();
                        }

                        @Override
                        public void draw(RawOitPass pass) {
                            NativeLodWater.draw(nativeWater.flywheel$waterShaders(), pass, () -> {
                                nativeWater.flywheel$prepareWater(params);
                                original.call(renderer, params, false, buffers, profiler);
                            });
                        }
                    });
            nativeWater.flywheel$finishWater();
            if (drawn) return;
        }
        original.call(renderer, params, opaque, buffers, profiler);
    }
}
