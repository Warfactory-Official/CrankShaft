package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.impl.FabulousReroute;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
abstract class LevelRendererTransparencyMixin {
    @Inject(method = "getTransparencyChain", at = @At("HEAD"), cancellable = true)
    private void flywheel$rerouteImprovedTransparency(CallbackInfoReturnable<@Nullable PostChain> cir) {
        if (FabulousReroute.active()) {
            cir.setReturnValue(null);
        }
    }
}
