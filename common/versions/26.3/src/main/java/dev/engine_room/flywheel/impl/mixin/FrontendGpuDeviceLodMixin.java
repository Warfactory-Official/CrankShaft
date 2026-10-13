package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.engine_room.flywheel.impl.compat.LodCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendGpuDevice.class)
abstract class FrontendGpuDeviceLodMixin {
    // Device-lived buffers and textures must be released before the frontend closes its native backend.
    @Inject(method = "close", at = @At("HEAD"))
    private void flywheel$closeLodResources(CallbackInfo ci) {
        LodCompat.destroyGpuResources();
    }
}
