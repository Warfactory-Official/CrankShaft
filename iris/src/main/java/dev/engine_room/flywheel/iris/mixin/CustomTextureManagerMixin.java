package dev.engine_room.flywheel.iris.mixin;

import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CustomTextureManager.class)
abstract class CustomTextureManagerMixin {
    // Resource images may upload on first lookup; pipeline construction runs outside the terrain render pass.
    @Inject(method = "createCustomTexture", at = @At("RETURN"))
    private void flywheel$loadResourceTexture(CustomTextureData data, CallbackInfoReturnable<TextureAccess> cir) {
        if (data instanceof CustomTextureData.ResourceData) cir.getReturnValue().getTextureId().getAsInt();
    }
}
