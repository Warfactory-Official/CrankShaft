package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.iris.compile.ContractProgramSet;
import dev.engine_room.flywheel.iris.engine.DeferredCompositePass;
import dev.engine_room.flywheel.iris.engine.DeferredOitRenderer;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompositeRenderer.class)
abstract class CompositeRendererMixin {
    @Shadow
    @Final
    private ImmutableList<?> passes;
    @Shadow
    @Final
    private RenderTargets renderTargets;
    @Shadow
    @Final
    private CustomUniforms customUniforms;
    @Shadow
    @Final
    private WorldRenderingPipeline pipeline;
    @Unique
    private @Nullable DeferredOitRenderer flywheel$deferred;
    @Unique
    private boolean flywheel$checked;

    // Pass selection precedes attached computes as well as graphics; Program.use alone misses compute-only passes.
    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableList;get(I)Ljava/lang/Object;"))
    private Object flywheel$beginCompositePass(ImmutableList<?> list, int index, Operation<Object> original) {
        Object entry = original.call(list, index);
        DeferredCompositePass current = (DeferredCompositePass) entry;
        if (!flywheel$checked) {
            flywheel$checked = true;
            if (pipeline instanceof IrisRenderingPipeline iris) {
                var resolver = ((IrisRenderingPipelineAccessor) iris).flywheel$resolver();
                var programs = (ContractProgramSet) ((ProgramFallbackResolverAccessor) resolver).flywheel$programs();
                if (programs.flywheel$deferredOit() != null) {
                    var plan = programs.flywheel$deferredOit().replayPlan(iris, passes);
                    if (plan != null) {
                        var holder = ((IrisRenderingPipelineAccessor) iris).flywheel$shaderStorageBuffers();
                        flywheel$deferred = DeferredOitRenderer.create(plan, passes, renderTargets, customUniforms,
                                ((ShaderStorageBufferHolderAccessor) holder).flywheel$buffers(),
                                iris.allowConcurrentCompute());
                    }
                }
            }
        }
        if (flywheel$deferred != null) flywheel$deferred.before(current);
        return entry;
    }

    @WrapMethod(method = "renderAll")
    private void flywheel$finishCompositeReplay(Operation<Void> original) {
        boolean completed = false;
        try {
            original.call();
            completed = true;
        } finally {
            if (flywheel$deferred != null) flywheel$deferred.finish(completed);
        }
    }

    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/uniforms/custom/CustomUniforms;push(Ljava/lang/Object;)V"))
    private void flywheel$bindReplayResources(CustomUniforms uniforms, Object program, Operation<Void> original) {
        original.call(uniforms, program);
        if (flywheel$deferred != null) flywheel$deferred.bindNativeResources(program);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$closeDeferred(CallbackInfo ci) {
        if (flywheel$deferred != null) flywheel$deferred.close();
    }
}
