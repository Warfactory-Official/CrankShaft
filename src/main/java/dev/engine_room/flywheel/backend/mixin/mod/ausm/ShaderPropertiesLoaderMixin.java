package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import com.luna.ausm.impl.pipeline.pack.ShaderPack;
import com.luna.ausm.impl.pipeline.pack.ShaderProperties;
import dev.engine_room.flywheel.backend.engine.shaderpack.AusmContractShaderProperties;
import dev.engine_room.flywheel.backend.engine.shaderpack.AusmShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.pack.ShaderPropertiesAccessors", remap = false)
public abstract class ShaderPropertiesLoaderMixin {
    // Properties and compiled sources must see the same atomic patch plan, including native emission target/blend changes.
    @ModifyVariable(method = "load(Lcom/luna/ausm/impl/pipeline/pack/ShaderPack;Ljava/util/Map;)Lcom/luna/ausm/impl/pipeline/pack/ShaderProperties;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static ShaderPack flw$patchResources(ShaderPack pack) {
        return AusmShaderPack.forProperties(pack);
    }

    @Inject(method = "load(Lcom/luna/ausm/impl/pipeline/pack/ShaderPack;Ljava/util/Map;)Lcom/luna/ausm/impl/pipeline/pack/ShaderProperties;",
            at = @At("RETURN"), require = 1)
    private static void flw$ownPlan(ShaderPack pack, Map<String, String> options,
                                    CallbackInfoReturnable<ShaderProperties> cir) {
        if (pack instanceof AusmShaderPack patched)
            AusmContractShaderProperties.of(cir.getReturnValue()).flywheel$patches(patched.plan());
    }
}
