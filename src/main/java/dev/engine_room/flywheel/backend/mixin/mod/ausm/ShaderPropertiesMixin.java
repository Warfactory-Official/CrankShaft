package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import dev.engine_room.flywheel.backend.engine.shaderpack.ContractPatches;
import dev.engine_room.flywheel.backend.engine.shaderpack.AusmContractShaderProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.pack.ShaderProperties", remap = false)
public abstract class ShaderPropertiesMixin implements AusmContractShaderProperties {
    @Unique
    private ContractPatches.Plan flywheel$patches;

    @Override
    public ContractPatches.Plan flywheel$patches() {
        return flywheel$patches;
    }

    @Override
    public void flywheel$patches(ContractPatches.Plan plan) {
        flywheel$patches = plan;
    }
}
