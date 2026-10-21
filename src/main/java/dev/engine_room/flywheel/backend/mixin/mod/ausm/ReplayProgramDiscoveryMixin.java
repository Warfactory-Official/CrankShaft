package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import com.luna.ausm.impl.pipeline.pack.ShaderPack;
import com.luna.ausm.impl.pipeline.pack.ShaderProperties;
import dev.engine_room.flywheel.backend.engine.shaderpack.AusmContractShaderProperties;
import dev.engine_room.flywheel.backend.engine.shaderpack.AusmShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.shader.ShaderProgramSet", remap = false)
public abstract class ReplayProgramDiscoveryMixin {
    // Added compute stages must exist during discovery, before the pipeline owns the loaded properties.
    @ModifyVariable(method = "load", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static ShaderPack flw$discoverReplayStages(ShaderPack pack, ShaderPack original,
                                                       ShaderProperties properties) {
        return AusmShaderPack.withPlan(pack, AusmContractShaderProperties.of(properties).flywheel$patches());
    }
}
