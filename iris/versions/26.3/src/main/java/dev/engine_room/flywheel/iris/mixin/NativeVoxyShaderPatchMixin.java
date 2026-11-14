package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A native-depth pack's Voxy callbacks keep Minecraft's raw reversed depth. Voxy patches them while the pack is still
 * being constructed, so the pack comes from its own argument rather than Iris's current pack.
 */
@Mixin(IrisShaderPatch.class)
abstract class NativeVoxyShaderPatchMixin {
    @WrapOperation(method = "makePatch", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/iris/IrisShaderPatch;stupidEvilHackFixForRevZThatReallyShouldntExistOrBeDoneEverNeedsFixing(Ljava/lang/String;)Ljava/lang/String;"))
    private static String flywheel$rawDepth(String source, Operation<String> forward,
                                            @Local(argsOnly = true) ShaderPack pack) {
        return pack.flywheel$nativeDepth() ? source : forward.call(source);
    }
}
