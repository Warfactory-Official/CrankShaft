package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.iris.compile.ForwardOit;
import dev.engine_room.flywheel.iris.compile.GuestPipelines;
import dev.engine_room.flywheel.iris.engine.DeferredCaptureFrame;
import dev.engine_room.flywheel.iris.engine.DeferredOitPool;
import dev.engine_room.flywheel.iris.engine.FarFrontDepth;
import net.irisshaders.iris.mixinterface.CustomPass;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompositeRenderer.class)
abstract class CompositeRendererMixin implements DeferredCaptureFrame {
    @Shadow
    @Final
    private ImmutableList<?> passes;
    @Shadow
    @Final
    private RenderTargets renderTargets;
    @Shadow
    @Final
    private WorldRenderingPipeline pipeline;
    @Unique
    private @Nullable DeferredOitPool flywheel$pool;
    @Unique
    private boolean flywheel$checked;
    @Unique
    private boolean flywheel$captures;
    @Unique
    private ForwardOit flywheel$forward;
    @Unique
    private CustomPass flywheel$forwardLast;

    // Only the composite phase that consumes the capture receives the capture boundary, and owns its pool.
    @Override
    public void flywheel$beginDeferredCapture() {
        if (!flywheel$captures) {
            flywheel$captures = true;
            var iris = (IrisRenderingPipeline) pipeline;
            var resolver = ((IrisRenderingPipelineAccessor) iris).flywheel$resolver();
            var profile = ((ProgramFallbackResolverAccessor) resolver).flywheel$programs().flywheel$deferredOit();
            if (profile != null) flywheel$pool = new DeferredOitPool(profile.pool(), renderTargets,
                    ((ShaderStorageBufferHolderAccessor) ((IrisRenderingPipelineAccessor) iris)
                            .flywheel$shaderStorageBuffers()).flywheel$buffers());
        }
        if (flywheel$pool != null) flywheel$pool.beginCaptureFrame();
        FarFrontDepth.begin(renderTargets);
    }

    // Pass selection precedes attached computes as well as graphics; Program.use alone misses compute-only passes.
    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableList;get(I)Ljava/lang/Object;"))
    private Object flywheel$beginCompositePass(ImmutableList<?> list, int index, Operation<Object> original) {
        Object entry = original.call(list, index);
        CustomPass current = (CustomPass) entry;
        if (!flywheel$checked) {
            flywheel$checked = true;
            if (pipeline instanceof IrisRenderingPipeline iris) {
                var resolver = ((IrisRenderingPipelineAccessor) iris).flywheel$resolver();
                var programs = ((ProgramFallbackResolverAccessor) resolver).flywheel$programs();
                flywheel$forward = programs.flywheel$forwardOit();
                if (flywheel$forward != null) {
                    int end = flywheel$forward.boundary(passes.stream().map(pass ->
                            ((CompositePassAccessor) pass).flywheel$name()).toList());
                    if (end >= 0) flywheel$forwardLast = (CustomPass) passes.get(end);
                }
            }
        }
        if (flywheel$forward != null && flywheel$forward.consume(((CompositePassAccessor) current).flywheel$name())) {
            flywheel$resolveForward(current, false);
        }
        return entry;
    }

    @WrapMethod(method = "renderAll")
    private void flywheel$finishComposite(Operation<Void> original) {
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

    @Unique
    private void flywheel$resolveForward(CustomPass pass, boolean after) {
        var current = (CompositePassAccessor) pass;
        int target = flywheel$forward.target();
        var color = renderTargets.get(target);
        int slot = after ? Ints.indexOf(current.flywheel$drawBuffers(), target) : -1;
        int texture = slot >= 0 ? current.flywheel$framebuffer().getColorAttachment(slot)
                : current.flywheel$readAlt().contains(target) ? color.getAltTexture() : color.getMainTexture();
        GuestPipelines.resolveForwardOit((IrisRenderingPipeline) pipeline, texture);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$closeDeferred(CallbackInfo _) {
        if (flywheel$pool != null) flywheel$pool.close();
    }
}
