package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.seibel.distanthorizons.common.render.blaze.postProcessing.BlazeVanillaFadeRenderer;
import com.seibel.distanthorizons.core.render.RenderParams;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsFade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlazeVanillaFadeRenderer.class)
abstract class DhBlazeVanillaFadeRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void flywheel$prepareFadeMask(RenderParams params, CallbackInfo ci) {
        DistantHorizonsFade.prepare(params);
    }

    // The fade overwrites every drawn pixel past its band with LoD colour, far entities included.
    @ModifyExpressionValue(method = "render", at = @At(value = "INVOKE",
            #if MC >= 26.3
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;getDepthTexture()Lcom/mojang/renderpearl/api/textures/GpuTexture;"))
            #else
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;getDepthTexture()Lcom/mojang/blaze3d/textures/GpuTexture;"))
            #endif
    private GpuTexture flywheel$fadeMask(GpuTexture original) {
        return DistantHorizonsFade.mask();
    }
}
