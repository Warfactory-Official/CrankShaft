package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.backend.engine.indirect.RawOitPass;
import dev.engine_room.flywheel.impl.compat.NativeLodWater;
import dev.engine_room.flywheel.impl.compat.NativeWaterReplay;
import dev.engine_room.flywheel.impl.far.FarHost;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MDICSectionRenderer.class)
abstract class VoxyWaterRenderMixin {
    @Shadow
    @Final
    private AbstractRenderPipeline pipeline;
    @Shadow
    @Final
    private Shader translucentTerrainShader;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$prepareWaterSource(CallbackInfo ci) {
        if (pipeline instanceof NormalRenderPipeline) {
            NativeLodWater.observeVoxy(translucentTerrainShader);
        }
    }

    @Inject(method = "renderOpaque(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V",
            at = @At("HEAD"))
    private void flywheel$selectWaterSource(MDICViewport viewport, CallbackInfo ci) {
        if (pipeline instanceof NormalRenderPipeline) {
            NativeLodWater.observeVoxy(translucentTerrainShader);
        }
    }

    @WrapMethod(method = "renderTranslucent(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V")
    private void flywheel$water(MDICViewport viewport, Operation<Void> original) {
        if (pipeline instanceof NormalRenderPipeline normal) {
            var target = new FarHost(((VoxyWaterTargetAccessor) normal).flywheel$waterColor().id,
                    pipeline.fb.getDepthTex().id, viewport.width, viewport.height, viewport.projection,
                    pipeline.properties.isZero2One(), pipeline.properties.isReverseZ(), 1);
            boolean drawn = NativeLodWater.render(target, new NativeWaterReplay() {
                @Override
                public boolean ready() {
                    return translucentTerrainShader.flywheel$waterShaders().ready();
                }

                @Override
                public void draw(RawOitPass pass) {
                    NativeLodWater.draw(translucentTerrainShader.flywheel$waterShaders(), pass,
                            () -> original.call(viewport));
                }
            });
            if (drawn) return;
        }
        original.call(viewport);
    }

    // Host setup binds all geometry/material resources before the OIT pass replaces its framebuffer and program.
    @Inject(method = "renderTranslucent(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/ARBIndirectParameters;glMultiDrawElementsIndirectCountARB(IIJJII)V"))
    private void flywheel$producerState(MDICViewport viewport, CallbackInfo ci) {
        NativeLodWater.bindProducer();
    }
}
