package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import dev.engine_room.flywheel.backend.engine.shaderpack.GuestRendering;
import dev.engine_room.flywheel.backend.engine.shaderpack.NothiriumTerrain;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.PipelineShadowRendering", remap = false)
public abstract class ShadowRenderingMixin {
    // Native shadow terrain has a separate selection and bypasses the main world-layer boundary.
    @WrapMethod(method = "renderShadowMap")
    private void flw$nativeTerrain(float partialTicks, Operation<Void> original) {
        if (!NothiriumTerrain.hostActive()) {
            original.call(partialTicks);
            return;
        }
        try (var scope = NothiriumTerrain.begin()) { original.call(partialTicks); }
    }

    // AUSM bypasses RenderGlobal.renderEntities; opaque visuals must precede the opaque-depth snapshot.
    @Inject(method = "renderShadowMap", at = @At(value = "INVOKE",
            target = "Lcom/luna/ausm/impl/pipeline/fbo/ShadowFramebuffer;copyDepthToSnapshot()V"), require = 1)
    private void flw$opaque(float partialTicks, CallbackInfo ci) {
        GuestRendering.renderShadowOpaque(partialTicks);
    }

    // The direct shadow terrain loop also bypasses RenderGlobal's translucent tail hook.
    @Inject(method = "renderShadowMap", at = @At(value = "INVOKE",
            target = "Lcom/luna/ausm/impl/pipeline/fbo/ShadowFramebuffer;generateShadowColorMipmaps()V"), require = 1)
    private void flw$translucent(float partialTicks, CallbackInfo ci) {
        GuestRendering.renderShadowTranslucent();
    }
}
