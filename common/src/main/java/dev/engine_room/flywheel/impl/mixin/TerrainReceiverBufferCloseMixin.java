package dev.engine_room.flywheel.impl.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({GlBuffer.Direct.class, VulkanGpuBuffer.Direct.class})
abstract class TerrainReceiverBufferCloseMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void flywheel$releaseReceivers(CallbackInfo _) {
        TerrainReceiverBuffers.release((GpuBuffer) (Object) this);
    }
}
