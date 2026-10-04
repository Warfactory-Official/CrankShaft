package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PipelineManager.class)
abstract class PipelineManagerMixin {
    // Guest program keys read the installed pipeline, which preparePipeline assigns just before returning.
    @Inject(method = "preparePipeline", at = @At("RETURN"), require = 1)
    private void flywheel$warmGuests(CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        if (cir.getReturnValue() instanceof IrisRenderingPipeline iris) GuestPipelines.warmUp(iris);
    }
}
