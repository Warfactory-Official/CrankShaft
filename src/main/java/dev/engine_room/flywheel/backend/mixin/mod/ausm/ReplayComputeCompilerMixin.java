package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.luna.ausm.api.pipeline.shader.ComputeProgramSource;
import com.luna.ausm.impl.pipeline.pack.ShaderPack;
import com.luna.ausm.impl.pipeline.pack.ShaderPackDirectives;
import com.luna.ausm.impl.pipeline.pack.ShaderProperties;
import com.luna.ausm.impl.pipeline.shader.ComputeProgram;
import dev.engine_room.flywheel.backend.engine.shaderpack.DeferredReplayCompilation;
import dev.engine_room.flywheel.backend.engine.shaderpack.AusmShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.shader.ComputeProgram", remap = false)
public abstract class ReplayComputeCompilerMixin {
    // Compute preprocessing has a separate entry point from native graphics compilation.
    @WrapMethod(method = "compile(Lcom/luna/ausm/impl/pipeline/pack/ShaderPack;Lcom/luna/ausm/impl/pipeline/pack/ShaderProperties;Lcom/luna/ausm/api/pipeline/shader/ComputeProgramSource;Lcom/luna/ausm/impl/pipeline/pack/ShaderPackDirectives;)Lcom/luna/ausm/impl/pipeline/shader/ComputeProgram;")
    private static ComputeProgram flw$replayScope(ShaderPack pack, ShaderProperties properties,
                                                  ComputeProgramSource source,
                                                  ShaderPackDirectives directives, Operation<ComputeProgram> original) {
        pack = AusmShaderPack.forCompilation(pack);
        var plan = pack instanceof AusmShaderPack patched ? patched.plan() : null;
        try (var scope = DeferredReplayCompilation.enter(plan, source.name())) {
            return original.call(pack, properties, source, directives);
        }
    }
}
