package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.compile.ExperimentalFlatten;
import dev.engine_room.flywheel.iris.compile.ExperimentalOit;
import dev.engine_room.flywheel.iris.compile.ExperimentalPipelines;
import dev.engine_room.flywheel.iris.compile.patches.PhotonSharedMemory;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IrisRenderingPipeline.class)
abstract class ExperimentalPipelineLifecycleMixin {
    @Shadow
    @Final
    private CompositeRenderer compositeRenderer;

    // Resizing and setup have finished; the begin passes clear capture storage immediately afterward.
    @Inject(method = "beginLevelRendering", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
    private void flywheel$beginDeferredCapture(CallbackInfo _) {
        compositeRenderer.flywheel$beginDeferredCapture();
    }

    @Inject(method = "<init>", at = @At(value = "FIELD",
            target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;packSamplers:Lnet/irisshaders/iris/samplers/PackSamplers;",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void flywheel$registerOitSamplers(CallbackInfo _) {
        ExperimentalOit.registerSamplers((IrisRenderingPipeline) (Object) this);
        ExperimentalFlatten.registerSamplers((IrisRenderingPipeline) (Object) this);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$releaseGuestPrograms(CallbackInfo _) {
        ExperimentalPipelines.release((IrisRenderingPipeline) (Object) this);
        ExperimentalOit.release((IrisRenderingPipeline) (Object) this);
        ExperimentalFlatten.release((IrisRenderingPipeline) (Object) this);
        PhotonSharedMemory.clear();
    }
}
