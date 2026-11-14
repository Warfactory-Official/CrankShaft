package dev.engine_room.flywheel.iris.experimental.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.iris.compile.ExperimentalOit;
import dev.engine_room.flywheel.iris.compile.ForwardOit;
import dev.engine_room.flywheel.iris.engine.DeferredCaptureFrame;
import dev.engine_room.flywheel.iris.engine.ExperimentalDeferredPool;
import dev.engine_room.flywheel.iris.mixin.IrisRenderingPipelineAccessor;
import dev.engine_room.flywheel.iris.mixin.ProgramFallbackResolverAccessor;
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

import java.util.Iterator;

@Mixin(CompositeRenderer.class)
abstract class ExperimentalCompositeRendererMixin implements DeferredCaptureFrame {
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
    private ExperimentalDeferredPool flywheel$pool;
    @Unique
    private boolean flywheel$checked;
    @Unique
    private boolean flywheel$captures;
    @Unique
    private ForwardOit flywheel$forward;
    @Unique
    private Object flywheel$forwardLast;

    // Only the composite phase that consumes the capture receives the capture boundary, and owns its pool.
    @Override
    public void flywheel$beginDeferredCapture() {
        if (!flywheel$captures) {
            flywheel$captures = true;
            var resolver = ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver();
            var profile = ((ProgramFallbackResolverAccessor) resolver).flywheel$programs().flywheel$deferredOit();
            if (profile != null) flywheel$pool = new ExperimentalDeferredPool(pipeline, renderTargets, profile.pool());
        }
        if (flywheel$pool != null) flywheel$pool.beginCaptureFrame();
    }

    @WrapMethod(method = "renderAll")
    private void flywheel$renderComposite(Operation<Void> original) {
        if (!flywheel$checked) {
            flywheel$checked = true;
            var resolver = ((IrisRenderingPipelineAccessor) pipeline).flywheel$resolver();
            var programs = ((ProgramFallbackResolverAccessor) resolver).flywheel$programs();
            flywheel$forward = programs.flywheel$forwardOit();
            if (flywheel$forward != null) {
                int end = flywheel$forward.boundary(passes.stream().map(pass ->
                        ((ExperimentalCompositePassMixin) pass).flywheel$name()).toList());
                if (end >= 0) flywheel$forwardLast = passes.get(end);
            }
        }
        boolean completed = false;
        try {
            original.call();
            if (flywheel$forwardLast != null && flywheel$forward.consume(ForwardOit.END))
                flywheel$resolveForward(flywheel$forwardLast, true);
            completed = true;
        } finally {
            if (flywheel$pool != null) flywheel$pool.finishFrame(completed);
            if (flywheel$forwardLast != null) flywheel$forward.finish();
        }
    }

    // The outer pass iterator precedes computes and mip generation; resolving at Program.draw would be too late.
    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE", ordinal = 0,
            target = "Ljava/util/Iterator;next()Ljava/lang/Object;"))
    private Object flywheel$resolveForward(Iterator<?> iterator, Operation<Object> original) {
        Object pass = original.call(iterator);
        var current = (ExperimentalCompositePassMixin) pass;
        if (flywheel$forward != null && flywheel$forward.consume(current.flywheel$name())) {
            flywheel$resolveForward(pass, false);
        }
        return pass;
    }

    @Unique
    private void flywheel$resolveForward(Object pass, boolean after) {
        var current = (ExperimentalCompositePassMixin) pass;
        int target = flywheel$forward.target();
        int slot = -1;
        if (after) {
            var attachments = current.flywheel$attachments();
            for (int i = 0; i < attachments.targets().size(); i++)
                if (attachments.targets().get(i).index() == target) slot = i;
        }
        var view = slot >= 0 ? current.flywheel$framebuffer().getColorViews().get(slot)
                : renderTargets.get(target).getTextureView(current.flywheel$readAlt().contains(target), false);
        ExperimentalOit.get(pipeline, false).compositeForward(view);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$closeDeferred(CallbackInfo _) {
        if (flywheel$pool != null) flywheel$pool.close();
    }
}
