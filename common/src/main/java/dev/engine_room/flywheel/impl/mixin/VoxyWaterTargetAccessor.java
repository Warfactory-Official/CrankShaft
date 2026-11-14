package dev.engine_room.flywheel.impl.mixin;

import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.gl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NormalRenderPipeline.class)
interface VoxyWaterTargetAccessor {
    @Accessor("colourSSAOTex")
    GlTexture flywheel$waterColor();
}
