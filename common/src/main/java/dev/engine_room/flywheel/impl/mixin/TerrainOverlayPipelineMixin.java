package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainOverlayDraw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#if MC >= 26.3
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;

@Mixin(FrontendRenderPass.class)
#else
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

@Mixin(RenderPass.class)
#endif
abstract class TerrainOverlayPipelineMixin {
    @Inject(method = "setPipeline", at = @At("RETURN"))
    private void flywheel$prepareOverlay(
            #if MC >= 26.3
            CompiledRenderPipeline pipeline,
            #else
            RenderPipeline pipeline,
            #endif
            CallbackInfo _) {
        #if MC >= 26.3
        boolean overlay=((FrontendRenderPipeline)pipeline).uniformIndices().containsKey("TerrainReceiverIds");
        #else
        boolean overlay = pipeline.getLocation().getPath().startsWith("pipeline/terrain_overlay/");
        #endif
        if (overlay) TerrainOverlayDraw.bind((RenderPass) (Object) this);
    }
}
