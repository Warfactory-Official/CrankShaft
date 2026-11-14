package dev.engine_room.flywheel.iris.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.GlGpuTimer;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.FinalPassRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GPU timer scopes for Iris's own passes while the GL timer runs: {@code iris/begin}, {@code iris/shadow},
 * {@code iris/prepare}, {@code iris/deferred}, {@code iris/translucent} (end of {@code beginTranslucents} to the
 * composite: translucent terrain, entities and guest draws), {@code iris/composite}, {@code iris/final}.
 */
@Mixin(IrisRenderingPipeline.class)
abstract class IrisPassTimerMixin {
    private static final String RENDER_ALL = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V";

    @Unique
    private boolean flywheel$timingShadow;
    @Unique
    private boolean flywheel$timingTranslucent;

    @Unique
    private static boolean flywheel$push(String name) {
        if (!GlGpuTimer.isEnabled()) {
            return false;
        }
        GlCompat.pushDebugGroup(name);
        return true;
    }

    @Unique
    private static void flywheel$composite(String name, CompositeRenderer renderer, Operation<Void> original) {
        boolean timed = flywheel$push(name);
        try {
            original.call(renderer);
        } finally {
            if (timed) GlCompat.popDebugGroup();
        }
    }

    @WrapOperation(method = "beginLevelRendering", at = @At(value = "INVOKE", target = RENDER_ALL))
    private void flywheel$timeBegin(CompositeRenderer renderer, Operation<Void> original) {
        flywheel$composite("iris/begin", renderer, original);
    }

    // Head to the prepare pass: the shadow renderer's signature carries Iris's own accessor type.
    @Inject(method = "renderShadows", at = @At("HEAD"))
    private void flywheel$beginShadowScope(CallbackInfo _) {
        flywheel$timingShadow = flywheel$push("iris/shadow");
    }

    @WrapOperation(method = "renderShadows", at = @At(value = "INVOKE", target = RENDER_ALL))
    private void flywheel$timePrepare(CompositeRenderer renderer, Operation<Void> original) {
        if (flywheel$timingShadow) GlCompat.popDebugGroup();
        flywheel$timingShadow = false;
        flywheel$composite("iris/prepare", renderer, original);
    }

    @WrapOperation(method = "beginTranslucents", at = @At(value = "INVOKE", target = RENDER_ALL))
    private void flywheel$timeDeferred(CompositeRenderer renderer, Operation<Void> original) {
        flywheel$composite("iris/deferred", renderer, original);
    }

    @Inject(method = "beginTranslucents", at = @At("RETURN"))
    private void flywheel$beginTranslucentScope(CallbackInfo _) {
        flywheel$timingTranslucent = flywheel$push("iris/translucent");
    }

    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"))
    private void flywheel$endTranslucentScope(CallbackInfo _) {
        if (flywheel$timingTranslucent) GlCompat.popDebugGroup();
        flywheel$timingTranslucent = false;
    }

    @WrapOperation(method = "finalizeLevelRendering", at = @At(value = "INVOKE", target = RENDER_ALL))
    private void flywheel$timeComposite(CompositeRenderer renderer, Operation<Void> original) {
        flywheel$composite("iris/composite", renderer, original);
    }

    @WrapOperation(method = "finalizeLevelRendering", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/FinalPassRenderer;renderFinalPass()V"))
    private void flywheel$timeFinal(FinalPassRenderer renderer, Operation<Void> original) {
        boolean timed = flywheel$push("iris/final");
        try {
            original.call(renderer);
        } finally {
            if (timed) GlCompat.popDebugGroup();
        }
    }
}
