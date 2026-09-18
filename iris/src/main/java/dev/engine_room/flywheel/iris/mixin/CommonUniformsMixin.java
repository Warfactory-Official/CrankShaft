package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.impl.BackendManagerImpl;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CommonUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommonUniforms.class)
abstract class CommonUniformsMixin {
    @Inject(method = "addDynamicUniforms", at = @At("RETURN"))
    private static void flywheel$deferredEnabled(DynamicUniformHolder uniforms, FogMode fogMode, CallbackInfo ci) {
        uniforms.uniform1b(UniformUpdateFrequency.PER_FRAME, "flw_oitActive", BackendManagerImpl::isBackendOn);
    }
}
