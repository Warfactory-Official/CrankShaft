package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import com.luna.ausm.api.pipeline.shader.RenderPass;
import com.luna.ausm.impl.pipeline.shader.ShaderProgram;
import dev.engine_room.flywheel.backend.engine.shaderpack.AusmReplayBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {"com.luna.ausm.impl.pipeline.PipelineRuntimeDiagnosticsState8",
        "com.luna.ausm.impl.pipeline.PipelineRuntimeDrawModeState"}, remap = false)
public abstract class ReplayProgramBindingMixin {
    // The host has rebound its storage and custom uniforms before capture is enabled.
    @Inject(method = "bindProgramResources", at = @At("RETURN"), require = 1)
    private void flw$captureEnabled(RenderPass pass, ShaderProgram program, CallbackInfo ci) {
        AusmReplayBridge.bindAusmProgram(program);
    }
}
