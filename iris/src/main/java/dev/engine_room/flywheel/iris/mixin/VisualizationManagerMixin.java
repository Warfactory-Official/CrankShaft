package dev.engine_room.flywheel.iris.mixin;

import dev.engine_room.flywheel.backend.engine.terrain.GuestTerrainGate;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainDispatcher;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VisualizationManagerImpl.class)
abstract class VisualizationManagerMixin {
    @Shadow
    private @Nullable TerrainDispatcher terrainDrawDispatcher;

    // The guest returns before the ordinary runOitChain finally rotates these terrain-owned uniform rings.
    @Inject(method = "renderTranslucentOitSodium", at = @At("RETURN"), require = 1)
    private void flywheel$finishGuestTerrain(CallbackInfoReturnable<Boolean> cir) {
        if (GuestTerrainGate.packActive && !ShadowRenderingState.areShadowsCurrentlyBeingRendered()
                && terrainDrawDispatcher != null) terrainDrawDispatcher.endFrame();
    }
}
