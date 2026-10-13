package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.backend.compile.NativeOitWarmup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OptionInstance.class)
abstract class ImprovedTransparencyOptionMixin {
    @Inject(method = "set", at = @At("RETURN"))
    private void flywheel$warmNativeTransparency(Object value, CallbackInfo ci) {
        if (NativeOitWarmup.ready() && (Object) this == Minecraft.getInstance().options.improvedTransparency()) {
            NativeOitWarmup.configurationChanged();
        }
    }
}
