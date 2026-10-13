package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.backend.engine.BerTranslucentCapture;
#if MC < 26.3
import dev.engine_room.flywheel.impl.FabulousReroute;
#endif
import dev.engine_room.flywheel.impl.LevelFrame;
import dev.engine_room.flywheel.impl.compat.LodCompat;
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
    private static BerTranslucentCapture flywheel$capture() {
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        return manager != null && !manager.isShaderPackGuest() ? manager.berTranslucent() : null;
    }

    #if MC < 26.3
    @Inject(method = "executeSolid", at = @At("HEAD"), require = 1)
    private void flywheel$captureOpaqueTerrain(CallbackInfo ci) {
        if (LevelFrame.owns(submitNodeStorage)) {
            LodCompat.captureTerrainDepth(0);
        }
    }
    #endif

    @Inject(method = "executeTranslucent", at = @At("HEAD"), require = 1)
    private void flywheel$beginOitFrame(CallbackInfo ci) {
        BerTranslucentCapture capture = flywheel$capture();
        if (capture != null && LevelFrame.owns(submitNodeStorage)) {
            capture.beginFrame();
            #if MC < 26.3
            FabulousReroute.beginLayerWindow();
            #endif
        }
    }

    #if MC < 26.3
    @Inject(method = "executeTranslucentAfterTerrain", at = @At("RETURN"), require = 1)
    private void flywheel$runDeferredOit(CallbackInfo ci) {
        if (!LevelFrame.owns(submitNodeStorage)) {
            return;
        }
        FabulousReroute.closeLayerWindow();
        VisualizationManagerImpl manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        if (manager != null && manager.runDeferredTranslucentOit()) {
            LodCompat.captureTerrainDepth(2);
        }
    }
    #endif

    @Inject(method = "executeTranslucent", at = @At("RETURN"), require = 1)
    private void flywheel$endOitFrame(CallbackInfo ci) {
        BerTranslucentCapture capture = flywheel$capture();
        if (capture != null) {
            capture.endFrame();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            #if MC >= 26.3
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;translucentModels:Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;",
            #else
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;translucentModels:Lnet/minecraft/client/renderer/feature/phase/TranslucentFeatureRenderPhase;",
            #endif
            opcode = Opcodes.GETFIELD), require = 1)
    private void flywheel$enterModelPhase(CallbackInfo ci) {
        BerTranslucentCapture capture = flywheel$capture();
        if (capture != null) {
            capture.enterCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            #if MC >= 26.3
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;nameTags:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;",
            #else
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;seeThroughNameTags:Lnet/minecraft/client/renderer/feature/phase/TranslucentFeatureRenderPhase;",
            #endif
            opcode = Opcodes.GETFIELD), require = 1)
    private void flywheel$exitModelPhase(CallbackInfo ci) {
        BerTranslucentCapture capture = flywheel$capture();
        if (capture != null) {
            capture.exitCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;translucentCustomGeometry:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flywheel$enterCustomGeometryPhase(CallbackInfo ci) {
        BerTranslucentCapture capture = flywheel$capture();
        if (capture != null) {
            capture.enterCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;shadows:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flywheel$exitCustomGeometryPhaseLoop(CallbackInfo ci) {
        BerTranslucentCapture capture = flywheel$capture();
        if (capture != null) {
            capture.exitCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;shapeOutlines:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flywheel$exitCustomGeometryPhaseTail(CallbackInfo ci) {
        BerTranslucentCapture capture = flywheel$capture();
        if (capture != null) {
            capture.exitCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            #if MC >= 26.3
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;translucentBlocksAndItems:Lnet/minecraft/client/renderer/feature/phase/FeatureRenderPhase;",
            #else
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;translucentBlocksAndItems:Lnet/minecraft/client/renderer/feature/phase/TranslucentFeatureRenderPhase;",
            #endif
            opcode = Opcodes.GETFIELD), require = 1)
    private void flywheel$enterBlockEntityPhase(CallbackInfo ci) {
        BerTranslucentCapture capture = flywheel$capture();
        if (capture != null) {
            capture.enterCapturePhase();
        }
    }

    @Inject(method = "executeTranslucent", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;breakingOverlay:Lnet/minecraft/client/renderer/feature/phase/SimpleFeatureRenderPhase;",
            opcode = Opcodes.GETFIELD), require = 1)
    private void flywheel$exitBlockEntityPhase(CallbackInfo ci) {
        BerTranslucentCapture capture = flywheel$capture();
        if (capture != null) {
            capture.exitCapturePhase();
        }
    }
}
