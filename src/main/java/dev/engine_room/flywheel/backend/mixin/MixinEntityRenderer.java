package dev.engine_room.flywheel.backend.mixin;

import dev.engine_room.flywheel.backend.engine.shaderpack.GuestRenderPass;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The config plugin rewrites RenderLib's merged bbox-cache calls after all mixins have injected.
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {
    // Native shadow terrain precedes visualization frame dispatch, and paused rendering uses a saved tick delta.
    @Inject(method = "renderWorldPass", at = @At("HEAD"), require = 1)
    private void flw$captureInterpolation(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        GuestRenderPass.setRenderPartialTicks(partialTicks);
    }
}
