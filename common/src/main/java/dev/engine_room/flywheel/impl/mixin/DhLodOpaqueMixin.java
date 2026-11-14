package dev.engine_room.flywheel.impl.mixin;

import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsCompat;
import dev.engine_room.flywheel.impl.compat.DhStaticVisuals;
import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LodRenderer.class)
abstract class DhLodOpaqueMixin {
    // Both DH engines have closed their terrain pass here; opaque depth excludes generic objects and water.
    @Inject(method = "renderTerrain(Lcom/seibel/distanthorizons/core/wrapperInterfaces/render/renderPass/IDhTerrainRenderer;Lcom/seibel/distanthorizons/core/render/RenderBufferHandler;Lcom/seibel/distanthorizons/core/render/RenderParams;ZLcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;)V",
            at = @At("RETURN"))
    private void flywheel$afterOpaque(IDhTerrainRenderer renderer, RenderBufferHandler buffers, RenderParams params,
                                      boolean opaque, IProfilerWrapper profiler, CallbackInfo ci) {
        if (opaque && !GuestTerrainGate.renderingShadow()) {
            var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
            if (manager != null) manager.prepareFarFrame();
            DhStaticVisuals.draw(params, buffers);
            DistantHorizonsCompat.afterOpaque(params);
        }
    }
}
