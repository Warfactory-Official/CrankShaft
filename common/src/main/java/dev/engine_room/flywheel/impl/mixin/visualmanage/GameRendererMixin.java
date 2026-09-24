package dev.engine_room.flywheel.impl.mixin.visualmanage;

import dev.engine_room.flywheel.impl.visualization.WorldRenderOwnership;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), require = 1)
    private void flywheel$beginLevelRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        WorldRenderOwnership.beginLevelRender();
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"), require = 1)
    private void flywheel$endLevelRender(DeltaTracker deltaTracker, CallbackInfo ci) {
        WorldRenderOwnership.endLevelRender();
    }
}
