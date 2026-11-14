package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
#if MC >= 26.3
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
#else
import com.mojang.blaze3d.systems.CommandEncoder;
#endif
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
#if MC >= 26.3
        FrontendCommandEncoder.class
#else
        CommandEncoder.class
#endif
)
abstract class TerrainReceiverBufferCopyMixin {
    @Inject(method = "copyToBuffer", at = @At("RETURN"))
    private void flywheel$moveReceivers(GpuBufferSlice source, GpuBufferSlice target, CallbackInfo _) {
        TerrainReceiverBuffers.copy(source, target);
    }
}
