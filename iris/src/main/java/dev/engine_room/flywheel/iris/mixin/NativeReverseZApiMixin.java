package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.apiimpl.IrisApiV0Impl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DH renders a native-depth pack's shadow pass reversed too.
 */
@Mixin(IrisApiV0Impl.class)
abstract class NativeReverseZApiMixin {
    @ModifyReturnValue(method = "isReverseZDuringShaders", at = @At("RETURN"))
    private boolean flywheel$nativeShadow(boolean reversed) {
        return reversed || NativeDepth.active();
    }
}
