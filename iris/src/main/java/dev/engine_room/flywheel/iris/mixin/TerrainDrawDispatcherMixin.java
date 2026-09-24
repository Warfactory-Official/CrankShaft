package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDrawDispatcher;
import dev.engine_room.flywheel.iris.compile.GuestSsbos;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TerrainDrawDispatcher.class)
abstract class TerrainDrawDispatcherMixin {
    @Unique
    private static void flywheel$restorePackBuffers() {
        if (GuestTerrainGate.enabled()
                && Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
            GuestSsbos.restore(pipeline);
        }
    }

    @Inject(method = {"drawOpaqueSolid", "drawShadowTerrain"}, at = @At("HEAD"), require = 1)
    private void flywheel$prepareGuestDraw(CallbackInfoReturnable<Boolean> cir) {
        GuestTerrainGate.packActive = GuestTerrainGate.enabled() && Iris.isPackInUseQuick();
    }

    @Inject(method = "prepareResidentTranslucent", at = @At("HEAD"), require = 1)
    private void flywheel$prepareGuestTranslucent(CallbackInfo ci) {
        GuestTerrainGate.packActive = GuestTerrainGate.enabled() && Iris.isPackInUseQuick();
    }

    // Registry uploads can bind scatter SSBOs even when no terrain draw follows. Restore at the whole operation's
    // boundary, not a program's clear callback or a later instance draw that might never happen.
    @Inject(method = {"drawOpaqueSolid", "drawShadowTerrain"}, at = @At("RETURN"), require = 1)
    private void flywheel$restoreAfterDraw(CallbackInfoReturnable<Boolean> cir) {
        flywheel$restorePackBuffers();
    }

    @Inject(method = "prepareResidentTranslucent", at = @At("RETURN"), require = 1)
    private void flywheel$restoreAfterPreparation(CallbackInfo ci) {
        flywheel$restorePackBuffers();
    }
}
