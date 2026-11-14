package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.impl.far.FarVisuals;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.iris.compile.FarDhOwner;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompatInternal;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VisualizationManagerImpl.class)
abstract class FarDhMainMixin {
    @Shadow
    @Final
    private @Nullable FarVisuals farVisuals;

    // DH drew before vanilla terrain. Its shared colour attachments need main depth for retained parts in front.
    @Inject(method = "render", at = @At("RETURN"))
    private void flywheel$drawMainFar(RenderContext context, CallbackInfo ci) {
        if (farVisuals == null || ShadowRenderingState.areShadowsCurrentlyBeingRendered()
                || !farVisuals.nearVanilla()) return;
        var surface = farVisuals.surface();
        if (surface != null && Iris.getPipelineManager()
                                   .getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
            var dh = (DHCompatInternal) pipeline.getDHCompat().getInstance();
            if (dh != null && dh.shouldOverride) {
                FarDhOwner owner = dh.getSolidShader();
                owner.flywheel$far().drawMain(surface, context);
            }
        }
    }
}
