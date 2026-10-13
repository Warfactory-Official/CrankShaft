package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.compile.ExperimentalOit;
import dev.engine_room.flywheel.iris.compile.ExperimentalPipelines;
import dev.engine_room.flywheel.iris.compile.patches.PhotonSharedMemory;
import dev.engine_room.flywheel.iris.engine.ExperimentalReplayProgram;
import dev.engine_room.flywheel.iris.engine.ExperimentalReplayState;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IrisRenderingPipeline.class)
abstract class ExperimentalPipelineLifecycleMixin {
    @Inject(method = "<init>", at = @At(value = "FIELD",
            target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;packSamplers:Lnet/irisshaders/iris/samplers/PackSamplers;",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void flywheel$registerOitSamplers(CallbackInfo ci) {
        ExperimentalReplayProgram.registerSamplers((IrisRenderingPipeline) (Object) this);
        ExperimentalOit.registerSamplers((IrisRenderingPipeline) (Object) this);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$releaseGuestPrograms(CallbackInfo ci) {
        ExperimentalPipelines.release((IrisRenderingPipeline) (Object) this);
        ExperimentalOit.release((IrisRenderingPipeline) (Object) this);
        PhotonSharedMemory.clear();
        ExperimentalReplayState.release();
    }
}
