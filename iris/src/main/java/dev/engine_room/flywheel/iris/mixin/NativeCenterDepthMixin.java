package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.NativeDepth;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The smoothed centre depth keeps the raw value for a native-depth pack; smoothing is affine.
 */
@Mixin(CenterDepthSampler.class)
abstract class NativeCenterDepthMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/program/ProgramBuilder;begin(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/google/common/collect/ImmutableSet;)Lnet/irisshaders/iris/gl/program/ProgramBuilder;"),
            index = 3)
    private String flywheel$rawCenterDepth(String fragment) {
        // Iris 1.11.4 already samples raw depth here.
        return NativeDepth.active() ? fragment.replace("1.0 - texture(depth,", "texture(depth,") : fragment;
    }
}
