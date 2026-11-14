package dev.engine_room.flywheel.iris.mixin;

#if MC >= 26.3

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.iris.compile.FarLayerIrisBridge;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reuses Voxy's exact captured native stages and draw buffers for the same graph that shades retained hits.
 */
@Mixin(MDICSectionRenderer.class)
abstract class FarVoxyWaterMixin {
    @Shadow
    @Final
    private AbstractRenderPipeline pipeline;
    @Shadow
    @Final
    private Shader translucentTerrainShader;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$observeWater(CallbackInfo ci) {
        if (pipeline instanceof IrisVoxyRenderPipeline iris)
            iris.flywheel$hostOit().source(translucentTerrainShader.flywheel$waterShaders());
    }

    @WrapMethod(method = "renderTranslucent(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V")
    private void flywheel$sharedWater(MDICViewport viewport, Operation<Void> original) {
        if (pipeline instanceof IrisVoxyRenderPipeline iris
                && FarLayerIrisBridge.voxyWater(iris, viewport, () -> original.call(viewport))) return;
        original.call(viewport);
    }

    @Inject(method = "renderTranslucent(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/ARBIndirectParameters;glMultiDrawElementsIndirectCountARB(IIJJII)V"))
    private void flywheel$bindProducer(MDICViewport viewport, CallbackInfo ci) {
        if (pipeline instanceof IrisVoxyRenderPipeline iris) iris.flywheel$hostOit().bind();
    }
}

#endif
