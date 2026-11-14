package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.NativeDepth;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.iris.VoxyUniforms;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A native-depth pack's {@code vxProj} family is Voxy's reversed projection itself.
 */
@Mixin(VoxyUniforms.class)
abstract class NativeVoxyUniformsMixin {
    @Inject(method = "conditionallyReverseRevZ", at = @At("HEAD"), cancellable = true)
    private static void flywheel$rawProjection(RenderProperties _, Matrix4f in, CallbackInfoReturnable<Matrix4f> cir) {
        if (NativeDepth.active()) cir.setReturnValue(in);
    }
}
