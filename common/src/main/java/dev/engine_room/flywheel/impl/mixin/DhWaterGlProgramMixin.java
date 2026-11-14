package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import dev.engine_room.flywheel.impl.compat.NativeWaterShaders;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlProgram.class)
abstract class DhWaterGlProgramMixin {
    @Shadow
    @Final
    private int programId;

    @Inject(method = "close", at = @At("HEAD"))
    private void flywheel$releaseWater(CallbackInfo ci) {
        NativeWaterShaders.releaseNative(programId);
    }
}
