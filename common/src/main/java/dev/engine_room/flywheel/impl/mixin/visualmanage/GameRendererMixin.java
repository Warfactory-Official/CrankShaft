package dev.engine_room.flywheel.impl.mixin.visualmanage;

import dev.engine_room.flywheel.impl.visualization.WorldRenderOwnership;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    #if MC >= 26.3
    @Inject(method = "renderLevel()V", at = @At("HEAD"), require = 1)
    #else
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), require = 1)
    #endif
    private void flywheel$beginLevelRender(CallbackInfo ci) {
        WorldRenderOwnership.beginLevelRender();
    }

    #if MC >= 26.3
    @Inject(method = "renderLevel()V", at = @At("RETURN"), require = 1)
    #else
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"), require = 1)
    #endif
    private void flywheel$endLevelRender(CallbackInfo ci) {
        WorldRenderOwnership.endLevelRender();
    }
}
