package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.backend.engine.BerTranslucentCapture;
import dev.engine_room.flywheel.impl.FabulousReroute;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets the vanilla translucent capture windows, scoped to the LEVEL frame; hand/GUI/PiP run the same methods but no OIT seam consumes captures there.
 */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
abstract class PreparedFrameMixin {
    @Shadow
    @Nullable
    private SubmitNodeStorage submitNodeStorage;

    @Unique
    @Nullable
    private static BerTranslucentCapture flw$capture() {
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        return manager != null ? manager.berTranslucent() : null;
    }

    @Inject(method = "executeTranslucent", at = @At("HEAD"), require = 1)
    private void flw$beginOitFrame(CallbackInfo ci) {
        BerTranslucentCapture capture = flw$capture();
        if (capture != null && FabulousReroute.isLevelFrame(submitNodeStorage)) {
            capture.beginFrame();
            FabulousReroute.beginLayerWindow();
        }
    }

    @Inject(method = "executeTranslucentAfterTerrain", at = @At("RETURN"), require = 1)
    private void flw$runDeferredOit(CallbackInfo ci) {
        if (!FabulousReroute.isLevelFrame(submitNodeStorage)) {
            return;
        }
        FabulousReroute.closeLayerWindow();
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager != null) {
            manager.runDeferredTranslucentOit();
        }
    }

    @Inject(method = "executeTranslucent", at = @At("RETURN"), require = 1)
    private void flw$endOitFrame(CallbackInfo ci) {
        BerTranslucentCapture capture = flw$capture();
        if (capture != null) {
            capture.endFrame();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;translucentModels:Lnet/minecraft/client/renderer/feature/phase/TranslucentFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flw$enterModelPhase(CallbackInfo ci) {
        BerTranslucentCapture capture = flw$capture();
        if (capture != null) {
            capture.enterCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;seeThroughNameTags:Lnet/minecraft/client/renderer/feature/phase/TranslucentFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flw$exitModelPhase(CallbackInfo ci) {
        BerTranslucentCapture capture = flw$capture();
        if (capture != null) {
            capture.exitCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;translucentCustomGeometry:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flw$enterCustomGeometryPhase(CallbackInfo ci) {
        BerTranslucentCapture capture = flw$capture();
        if (capture != null) {
            capture.enterCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;shadows:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flw$exitCustomGeometryPhaseLoop(CallbackInfo ci) {
        BerTranslucentCapture capture = flw$capture();
        if (capture != null) {
            capture.exitCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;shapeOutlines:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flw$exitCustomGeometryPhaseTail(CallbackInfo ci) {
        BerTranslucentCapture capture = flw$capture();
        if (capture != null) {
            capture.exitCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;translucentBlocksAndItems:Lnet/minecraft/client/renderer/feature/phase/TranslucentFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flw$enterBlockEntityPhase(CallbackInfo ci) {
        BerTranslucentCapture capture = flw$capture();
        if (capture != null) {
            capture.enterCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;breakingOverlay:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flw$exitBlockEntityPhase(CallbackInfo ci) {
        BerTranslucentCapture capture = flw$capture();
        if (capture != null) {
            capture.exitCapturePhase();
        }
    }
}
