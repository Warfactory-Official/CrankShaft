package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.impl.compat.VoxyCompat;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NormalRenderPipeline.class)
abstract class VoxyNormalRenderPipelineMixin {
    @Shadow
    private GlTexture colourTex;

    // Opaque LoDs are down; SSAO, translucent LoDs and the fogged blit into the main target follow.
    @Inject(method = "postOpaquePreTranslucent", at = @At("HEAD"))
    private void flywheel$drawFarVisuals(Viewport<?> viewport, int sourceDepthTexture, CallbackInfo ci) {
        VoxyCompat.drawFarVisuals((NormalRenderPipeline) (Object) this, colourTex.id, viewport);
    }
}
