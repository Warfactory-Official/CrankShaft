package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.impl.compat.VoxyCompat;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporal opaque terrain has finished; host post-processing, far visuals and translucent terrain follow.
 */
@Mixin(AbstractRenderPipeline.class)
abstract class VoxyOpaqueFarMixin {
    @Inject(method = "runPipeline", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/AbstractRenderPipeline;postOpaquePreTranslucent(Lme/cortex/voxy/client/core/rendering/Viewport;I)V"))
    private void flywheel$afterOpaque(Viewport<?> viewport, int sourceDepth, int _,
                                      int sourceWidth, int sourceHeight, CallbackInfo _) {
        VoxyCompat.afterOpaque((AbstractRenderPipeline) (Object) this, viewport, sourceDepth, sourceWidth,
                sourceHeight);
    }
}
