package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(Shader.Builder.class)
abstract class VoxyWaterShaderBuilderMixin {
    @Shadow
    @Final
    private Map<String, String> defines;
    @Unique
    private String flywheel$vertex;
    @Unique
    private String flywheel$fragment;

    // Voxy deletes its stage objects after linking; capture the exact source at its final compile boundary.
    @WrapOperation(method = "compileToProgram", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/gl/shader/Shader$Builder;createShader(Lme/cortex/voxy/client/core/gl/shader/ShaderType;Ljava/lang/String;)I"))
    private int flywheel$captureStage(ShaderType type, String source, Operation<Integer> original) {
        if (defines.containsKey("TRANSLUCENT")) {
            if (type == ShaderType.VERTEX) flywheel$vertex = source;
            if (type == ShaderType.FRAGMENT) flywheel$fragment = source;
        }
        return original.call(type, source);
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void flywheel$attachStages(CallbackInfoReturnable<Shader> cir) {
        if (defines.containsKey("TRANSLUCENT")) {
            cir.getReturnValue().flywheel$waterSources(flywheel$vertex, flywheel$fragment);
        }
    }
}
