package dev.engine_room.flywheel.impl.mixin;

import com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlVanillaFadeRenderer;
import com.seibel.distanthorizons.core.render.RenderParams;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsFade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlVanillaFadeRenderer.class)
abstract class DhGlVanillaFadeRendererMixin {
    // Before its raw GL state; GlDhVanillaFadeShader samples the result.
    @Inject(method = "render", at = @At("HEAD"))
    private void flywheel$prepareFadeMask(RenderParams params, CallbackInfo ci) {
        DistantHorizonsFade.prepare(params);
    }
}
