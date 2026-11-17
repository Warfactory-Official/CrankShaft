package dev.engine_room.flywheel.impl.mixin;

import dev.engine_room.flywheel.backend.compile.ShaderWarmup;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
abstract class ShaderManagerMixin {
    @Inject(method = "apply(Lcom/mojang/renderpearl/api/device/GpuDevice;Lnet/minecraft/client/renderer/ShaderManager$PendingResults;)V",
            at = @At("HEAD"))
    private void flywheel$releasePrograms(CallbackInfo _) {
        // Closes the replaced current pipeline cache, which holds every published program.
        ShaderWarmup.invalidated();
    }
}
