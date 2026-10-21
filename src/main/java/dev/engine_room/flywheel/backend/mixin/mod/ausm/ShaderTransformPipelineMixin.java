package dev.engine_room.flywheel.backend.mixin.mod.ausm;

import com.luna.ausm.api.pipeline.shader.RenderPass;
import dev.engine_room.flywheel.backend.engine.shaderpack.DeferredReplayCompilation;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.ShaderCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.luna.ausm.impl.pipeline.pack.ShaderTransformPipeline", remap = false)
public abstract class ShaderTransformPipelineMixin {
    // Material capture is inserted after host preprocessing and before its legacy interface lowering.
    @ModifyVariable(method = "transform", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static String flw$deferredMaterial(String value, String originalSource, int shaderType, RenderPass pass) {
        return DeferredReplayCompilation.transform(value, shaderType);
    }

    // AUSM can introduce modern qualifiers after preprocessing a GLSL 120 pack, or retain legacy varying with flat.
    @Inject(method = "transform", at = @At("RETURN"), cancellable = true, require = 1)
    private static void flw$normalizeInterface(String source, int shaderType, RenderPass pass,
                                               CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(ShaderCompatibility.normalize(cir.getReturnValue(), shaderType));
    }
}
