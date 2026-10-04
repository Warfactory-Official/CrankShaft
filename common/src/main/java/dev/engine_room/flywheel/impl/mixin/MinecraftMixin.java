package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    // 26.2: every resource listener has applied here, including downstream visual model/material initialization.
    @Inject(method = "onResourceLoadFinished", at = @At("HEAD"))
    private void flw$warmVisualShaders(CallbackInfo ci) {
        ShaderWarmup.warm();
    }
}
