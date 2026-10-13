package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlDhVanillaFadeShader;
import dev.engine_room.flywheel.impl.compat.DistantHorizonsFade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GlDhVanillaFadeShader.class)
abstract class DhGlDhVanillaFadeShaderMixin {
    // The fade overwrites every drawn pixel past its band with LoD colour, far entities included.
    @ModifyExpressionValue(method = "onRender", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IMinecraftRenderWrapper;getGlDepthTextureId()I"))
    private int flywheel$fadeMask(int original) {
        return DistantHorizonsFade.maskGlId();
    }
}
