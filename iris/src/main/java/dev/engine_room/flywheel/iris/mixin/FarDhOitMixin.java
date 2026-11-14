package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.engine_room.flywheel.iris.compile.FarDhOit;
import net.irisshaders.iris.compat.dh.DHCompatInternal;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replay program selection keeps DH's own per-buffer model offsets on the current shared-graph producer.
 */
@Mixin(DHCompatInternal.class)
abstract class FarDhOitMixin {
    @ModifyReturnValue(method = "getTranslucentShader", at = @At("RETURN"))
    private IrisLodRenderProgram flywheel$hostProducer(IrisLodRenderProgram nativeProgram) {
        var producer = FarDhOit.nativeProgram();
        return producer == null ? nativeProgram : producer;
    }
}
