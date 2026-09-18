package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.engine_room.flywheel.iris.compile.ContractProgramSet;
import dev.engine_room.flywheel.iris.compile.ContractShaderPack;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IrisRenderingPipeline.class)
abstract class IrisRenderingPipelineMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;getBufferObjects()Lit/unimi/dsi/fastutil/ints/Int2ObjectArrayMap;"))
    private Int2ObjectArrayMap<BuiltShaderStorageInfo> flywheel$acceptedBuffers(ShaderPack pack,
                                                                                Operation<Int2ObjectArrayMap<BuiltShaderStorageInfo>> original,
                                                                                @Local(argsOnly = true) ProgramSet programs) {
        if (((ContractShaderPack) pack).flywheel$deferredOit() != null
                && ((ContractProgramSet) programs).flywheel$deferredOit() == null) {
            // This profile's three buffers have no native pack owner.
            return new Int2ObjectArrayMap<>();
        }
        return original.call(pack);
    }

    @Inject(method = "destroy", at = @At("HEAD"), require = 1)
    private void flywheel$releaseGuestPrograms(CallbackInfo ci) {
        GuestPipelines.release((IrisRenderingPipeline) (Object) this);
    }
}
