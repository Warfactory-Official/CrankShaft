package dev.engine_room.flywheel.impl.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverBuffers;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverMeshes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.nio.ByteBuffer;

@Mixin(UberGpuBuffer.class)
abstract class UberGpuBufferReceiverMixin<T> {
    @Shadow
    @Final
    private int bufferUsage;
    @Shadow
    @Final
    private int alignSize;

    @ModifyVariable(method = "addAllocation", at = @At("HEAD"), argsOnly = true)
    private UberGpuBuffer.UploadCallback<T> flywheel$receiverUpload(UberGpuBuffer.UploadCallback<T> callback,
                                                                    @Local(argsOnly = true) ByteBuffer vertices) {
        if ((bufferUsage & GpuBuffer.USAGE_VERTEX) == 0) return callback;
        var ids = TerrainReceiverMeshes.find(vertices);
        int count = vertices.remaining() / alignSize;
        return key -> {
            var owner = (UberGpuBuffer<T>) (Object) this;
            var allocation = owner.getAllocation(key);
            TerrainReceiverBuffers.upload(owner.getGpuBuffer(allocation), alignSize,
                    allocation.getOffsetFromHeap() / alignSize, count, ids);
            callback.bufferHasBeenUploaded(key);
        };
    }
}
