package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.backend.opengl.GlCommandEncoder;
import dev.engine_room.flywheel.impl.compat.NativeLodWater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
#if MC >= 26.3
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#else
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
#endif

@Mixin(GlCommandEncoder.class)
abstract class DhWaterGlCommandEncoderMixin {
    // Blaze uploads/binds its original UBOs and textures first; only the final draw program and OIT state change.
    #if MC >= 26.3
    @Inject(method = "setupDraw", at = @At("RETURN"))
    private void flywheel$waterProducer(CallbackInfo ci) {
        NativeLodWater.bindProducerWithNativeCull();
    }
    #else
    @Inject(method = "trySetup", at = @At("RETURN"))
    private void flywheel$waterProducer(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) NativeLodWater.bindProducerWithNativeCull();
    }
    #endif
}
