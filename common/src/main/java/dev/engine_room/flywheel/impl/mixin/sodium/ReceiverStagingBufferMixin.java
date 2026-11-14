package dev.engine_room.flywheel.impl.mixin.sodium;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverBuffers;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverMeshes;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainVertexFormat;
import net.caffeinemc.mods.sodium.client.gpu.arena.staging.MojangStagingBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(MojangStagingBuffer.class)
abstract class ReceiverStagingBufferMixin {
    @Inject(method = "enqueueCopy", at = @At("RETURN"))
    private void flywheel$uploadReceivers(ByteBuffer data, GpuBuffer vertices, long offset, CallbackInfo _) {
        if ((vertices.usage() & GpuBuffer.USAGE_VERTEX) == 0) return;
        int stride = TerrainVertexFormat.strideBytes();
        TerrainReceiverBuffers.upload(vertices, stride, offset / stride, data.remaining() / stride,
                TerrainReceiverMeshes.find(data));
    }
}
