package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.luna.ausm.api.pipeline.shader.RenderPass;
import com.luna.ausm.api.pipeline.shader.ShaderProgramSource;
import com.luna.ausm.impl.pipeline.pack.ShaderPack;
import com.luna.ausm.impl.pipeline.pack.ShaderPackDirectives;
import com.luna.ausm.impl.pipeline.pack.ShaderProperties;
import com.luna.ausm.impl.pipeline.shader.ShaderProgram;
import dev.engine_room.flywheel.backend.engine.shaderpack.DeferredReplayCompilation;
import dev.engine_room.flywheel.backend.engine.shaderpack.AusmShaderPack;
import net.minecraftforge.fml.common.Loader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.shader.ShaderCompiler", remap = false)
public abstract class ShaderCompilerMixin {
    // The host preprocesses multiple stages inside this call; keep their native program identity attached.
    @WrapMethod(method = "compileSource(Lcom/luna/ausm/impl/pipeline/pack/ShaderPack;Lcom/luna/ausm/impl/pipeline/pack/ShaderProperties;Lcom/luna/ausm/api/pipeline/shader/ShaderProgramSource;Lcom/luna/ausm/api/pipeline/shader/RenderPass;Ljava/lang/String;Lcom/luna/ausm/impl/pipeline/pack/ShaderPackDirectives;)Lcom/luna/ausm/impl/pipeline/shader/ShaderProgram;")
    private static ShaderProgram flw$replayScope(ShaderPack pack, ShaderProperties properties,
                                                 ShaderProgramSource source,
                                                 RenderPass pass, String name, ShaderPackDirectives directives,
                                                 Operation<ShaderProgram> original) {
        pack = AusmShaderPack.forCompilation(pack);
        var plan = pack instanceof AusmShaderPack patched ? patched.plan() : null;
        try (var scope = DeferredReplayCompilation.enter(plan, name)) {
            return original.call(pack, properties, source, pass, name, directives);
        }
    }

    // Native programs and guests share the property-owned plan; AUSM retains ownership of its compiled programs.
    @ModifyVariable(method = "compileSource(Lcom/luna/ausm/impl/pipeline/pack/ShaderPack;Lcom/luna/ausm/impl/pipeline/pack/ShaderProperties;Lcom/luna/ausm/api/pipeline/shader/ShaderProgramSource;Lcom/luna/ausm/api/pipeline/shader/RenderPass;Ljava/lang/String;Lcom/luna/ausm/impl/pipeline/pack/ShaderPackDirectives;)Lcom/luna/ausm/impl/pipeline/shader/ShaderProgram;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static ShaderPack flw$patchResources(ShaderPack pack) {
        return AusmShaderPack.forCompilation(pack);
    }

    // DH wrappers are stage guards without a shader body when DH is absent; compiling them reports spurious pack failures.
    @Inject(method = "compileSource(Lcom/luna/ausm/impl/pipeline/pack/ShaderPack;Lcom/luna/ausm/impl/pipeline/pack/ShaderProperties;Lcom/luna/ausm/api/pipeline/shader/ShaderProgramSource;Lcom/luna/ausm/api/pipeline/shader/RenderPass;Ljava/lang/String;Lcom/luna/ausm/impl/pipeline/pack/ShaderPackDirectives;)Lcom/luna/ausm/impl/pipeline/shader/ShaderProgram;",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void flw$skipAbsentDh(ShaderPack pack, ShaderProperties properties, ShaderProgramSource source,
                                         RenderPass pass, String name, ShaderPackDirectives directives,
                                         CallbackInfoReturnable<ShaderProgram> cir) {
        if ((pass == RenderPass.DH_TERRAIN || pass == RenderPass.DH_WATER) && !Loader.isModLoaded("distanthorizons"))
            cir.setReturnValue(null);
    }
}
