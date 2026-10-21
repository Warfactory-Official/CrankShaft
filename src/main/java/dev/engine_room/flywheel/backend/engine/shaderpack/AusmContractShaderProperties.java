package dev.engine_room.flywheel.backend.engine.shaderpack;

import com.luna.ausm.impl.pipeline.pack.ShaderProperties;

public interface AusmContractShaderProperties {
    static AusmContractShaderProperties of(ShaderProperties properties) {
        return (AusmContractShaderProperties) (Object) properties;
    }

    ContractPatches.Plan flywheel$patches();

    void flywheel$patches(ContractPatches.Plan plan);
}
