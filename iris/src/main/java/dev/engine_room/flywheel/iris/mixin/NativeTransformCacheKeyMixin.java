package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Identical sources transform differently under the native depth contract.
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher$CacheKey")
abstract class NativeTransformCacheKeyMixin implements NativeDepth.Keyed {
    @Unique
    private final boolean flywheel$nativeDepth = NativeDepth.active();

    @Override
    public boolean flywheel$nativeDepth() {
        return flywheel$nativeDepth;
    }

    @ModifyReturnValue(method = "hashCode", at = @At("RETURN"))
    private int flywheel$hashNativeDepth(int hash) {
        return 31 * hash + Boolean.hashCode(flywheel$nativeDepth);
    }

    @ModifyReturnValue(method = "equals", at = @At("RETURN"))
    private boolean flywheel$equalNativeDepth(boolean equal, Object other) {
        return equal && ((NativeDepth.Keyed) other).flywheel$nativeDepth() == flywheel$nativeDepth;
    }
}
