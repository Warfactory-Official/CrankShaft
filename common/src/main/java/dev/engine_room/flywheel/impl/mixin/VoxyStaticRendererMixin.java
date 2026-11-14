package dev.engine_room.flywheel.impl.mixin;

#if MC >= 26.3

import dev.engine_room.flywheel.impl.compat.VoxyStaticVisuals;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MDICSectionRenderer.class)
abstract class VoxyStaticRendererMixin {
    @Shadow
    @Final
    private AbstractRenderPipeline pipeline;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flywheel$activateStaticSections(AbstractRenderPipeline pipeline, ModelStore models,
                                                 BasicSectionGeometryData geometry, CallbackInfo _) {
        geometry.flywheel$staticSections().activate(pipeline);
    }

    // The host has finished selecting and testing sections; commands reuse that exact visibility result.
    @Inject(method = "buildDrawCalls", at = @At("RETURN"))
    private void flywheel$buildStaticCommands(MDICViewport viewport, CallbackInfo _) {
        var self = (MDICSectionRenderer) (Object) this;
        var geometry = self.getGeometryManager();
        geometry.flywheel$staticSections().build(viewport, geometry);
    }

    // Current commands follow traversal; the preceding opaque draw still consumes the prior frame's commands.
    @Inject(method = "renderTemporal", at = @At("RETURN"))
    private void flywheel$drawNativeStatic(MDICViewport viewport, CallbackInfo _) {
        if (pipeline instanceof NormalRenderPipeline) VoxyStaticVisuals.draw(pipeline, viewport);
    }
}

#endif
