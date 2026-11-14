package dev.engine_room.flywheel.iris.mixin;

#if MC >= 26.3

import dev.engine_room.flywheel.impl.far.FarVisuals;
import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.iris.compile.FarVoxyProgram;
import dev.engine_room.flywheel.iris.compile.FarLayerVoxyOwner;
import dev.engine_room.flywheel.iris.compile.FarLayerVoxyProgram;
import dev.engine_room.flywheel.iris.compile.FarLayerIrisBridge;
import dev.engine_room.flywheel.iris.compile.FarVoxyOit;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opaque pack targets are ready, and their updated depth is about to be handed to Voxy's translucent pass.
 */
@Mixin(IrisVoxyRenderPipeline.class)
abstract class FarVoxyPipelineMixin implements FarLayerVoxyOwner {
    @Unique
    private @Nullable FarVoxyProgram flywheel$farProgram;
    @Unique
    private @Nullable FarLayerVoxyProgram flywheel$layeredFar;
    @Unique
    private @Nullable FarVoxyOit flywheel$hostOit;

    @Override
    public FarVoxyOit flywheel$hostOit() {
        if (flywheel$hostOit == null)
            flywheel$hostOit = new FarVoxyOit((IrisVoxyRenderPipeline) (Object) this);
        return flywheel$hostOit;
    }

    @Override
    public @Nullable FarLayerVoxyProgram flywheel$layeredFar() {
        var pipeline = (IrisVoxyRenderPipeline) (Object) this;
        if (pipeline._getData().translucentFragPatch() == null) return null;
        if (flywheel$layeredFar == null)
            flywheel$layeredFar = new FarLayerVoxyProgram(pipeline, flywheel$hostOit().graph());
        return flywheel$layeredFar;
    }

    @Inject(method = "postOpaquePreTranslucent", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL45C;glBlitNamedFramebuffer(IIIIIIIIIIII)V"))
    private void flywheel$farOpaque(Viewport<?> viewport, int sourceDepth, CallbackInfo ci) {
        var pipeline = (IrisVoxyRenderPipeline) (Object) this;
        VoxyStaticVisuals.draw(pipeline, (MDICViewport) viewport);
        var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        FarVisuals far = manager == null ? null : manager.farVisuals();
        var surface = far == null ? null : far.surface();
        if (surface == null) return;
        if (flywheel$farProgram == null) flywheel$farProgram = new FarVoxyProgram(pipeline);
        flywheel$farProgram.draw(pipeline, viewport, sourceDepth, surface);
    }

    @Inject(method = "free", at = @At("HEAD"))
    private void flywheel$freeFar(CallbackInfo ci) {
        if (flywheel$hostOit != null) {
            flywheel$hostOit.close();
            flywheel$hostOit = null;
        }
        if (flywheel$layeredFar != null) {
            flywheel$layeredFar.close();
            flywheel$layeredFar = null;
        }
        if (flywheel$farProgram != null) {
            flywheel$farProgram.close();
            flywheel$farProgram = null;
        }
    }
}

#endif
