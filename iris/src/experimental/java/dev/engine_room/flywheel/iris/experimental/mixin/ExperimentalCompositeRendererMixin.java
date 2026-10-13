package dev.engine_room.flywheel.iris.experimental.mixin;

import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.iris.engine.ExperimentalDeferredReplay;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompositeRenderer.class)
abstract class ExperimentalCompositeRendererMixin {
    @Shadow
    @Final
    private ImmutableList<?> passes;
    @Shadow
    @Final
    private RenderTargets renderTargets;
    @Shadow
    @Final
    private IrisRenderingPipeline pipeline;
    @Unique
    private ExperimentalDeferredReplay flywheel$replay;
    @Unique
    private boolean flywheel$checked;

    @WrapMethod(method = "renderAll")
    private void flywheel$replayMaterials(Operation<Void> original) {
        if (!flywheel$checked) {
            flywheel$checked = true;
            var resolver = ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver();
            var programs = ((ProgramFallbackResolverAccessor) resolver).flywheel$programs();
            var profile = programs.flywheel$deferredOit();
            if (profile != null)
                flywheel$replay = ExperimentalDeferredReplay.create(pipeline, renderTargets, passes, profile);
        }
        if (flywheel$replay == null) original.call();
        else flywheel$replay.render();
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$destroyReplay(CallbackInfo ci) {
        if (flywheel$replay != null) flywheel$replay.close();
    }
}
