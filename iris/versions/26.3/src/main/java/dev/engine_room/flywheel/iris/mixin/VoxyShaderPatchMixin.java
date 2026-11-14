package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.engine_room.flywheel.iris.compile.VoxyBuiltins;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Both native terrain and retained surfaces consume these callbacks after the pack's resource preprocessing.
 */
@Mixin(IrisShaderPatch.class)
abstract class VoxyShaderPatchMixin {
    @ModifyReturnValue(method = {"getPatchOpaqueSource", "getPatchTranslucentSource"}, at = @At("RETURN"))
    private String flywheel$completeTerrainBuiltins(String source) {
        return VoxyBuiltins.patch(source);
    }
}
