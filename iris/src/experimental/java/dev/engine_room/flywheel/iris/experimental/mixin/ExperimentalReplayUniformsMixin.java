package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.engine.ExperimentalReplayState;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CommonUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommonUniforms.class)
abstract class ExperimentalReplayUniformsMixin {
    @Inject(method = "addDynamicUniforms", at = @At("RETURN"))
    private static void flywheel$replayUniforms(DynamicUniformHolder uniforms, FogMode fog, CallbackInfo ci) {
        uniforms.uniform1i(UniformUpdateFrequency.CUSTOM, "flw_oitFarLayer",
                () -> ExperimentalReplayState.layer == 0 ? 0 : 1);
        uniforms.uniform1i(UniformUpdateFrequency.CUSTOM, "flw_oitLayerIndex", () -> ExperimentalReplayState.layer);
        uniforms.uniform1b(UniformUpdateFrequency.CUSTOM, "flw_oitReplaying", () -> ExperimentalReplayState.replaying);
    }
}
