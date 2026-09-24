package dev.engine_room.flywheel.iris.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.iris.compile.ContractProgramSet;
import dev.engine_room.flywheel.iris.engine.DeferredOitRenderer;
import net.irisshaders.iris.gl.program.Program;
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

    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/program/Program;use()V"))
    private void flywheel$replayDeferredLayers(Program program, Operation<Void> original) {
        if (!flywheel$checked) {
            flywheel$checked = true;
            if (pipeline instanceof IrisRenderingPipeline iris) {
                var resolver = ((IrisRenderingPipelineAccessor) iris).flywheel$resolver();
                var programs = (ContractProgramSet) ((ProgramFallbackResolverAccessor) resolver).flywheel$programs();
                if (programs.flywheel$deferredOit() != null) {
                    var holder = ((IrisRenderingPipelineAccessor) iris).flywheel$shaderStorageBuffers();
                    flywheel$deferred = DeferredOitRenderer.create(renderTargets, passes, customUniforms,
                            ((ShaderStorageBufferHolderAccessor) holder).flywheel$buffers()[1]);
                }
            }
        }
        if (flywheel$deferred != null) flywheel$deferred.before(program);
        original.call(program);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$closeDeferred(CallbackInfo ci) {
        if (flywheel$deferred != null) flywheel$deferred.close();
    }
}
