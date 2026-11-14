package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverCompilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverMesh;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverMeshes;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

@Mixin(MeshData.class)
abstract class MeshDataReceiverMixin implements TerrainReceiverMesh {
    @Unique
    private int[] flywheel$receivers = TerrainReceiverCompilation.EMPTY;
    @Unique
    private long flywheel$receiverAddress;

    @Override
    public int[] flywheel$receiverIds() {
        return flywheel$receivers;
    }

    @Override
    public void flywheel$receiverIds(int[] ids) {
        flywheel$receivers = ids;
    }

    @Inject(method = "vertexBuffer", at = @At("RETURN"))
    private void flywheel$publishReceiverRange(CallbackInfoReturnable<ByteBuffer> cir) {
        if (flywheel$receivers.length == 0) return;
        long address = MemoryUtil.memAddress(cir.getReturnValue());
        if (flywheel$receiverAddress != 0 && flywheel$receiverAddress != address)
            TerrainReceiverMeshes.remove(flywheel$receiverAddress, flywheel$receivers);
        TerrainReceiverMeshes.publish(address, flywheel$receivers);
        flywheel$receiverAddress = address;
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void flywheel$retireReceiverRange(CallbackInfo _) {
        if (flywheel$receiverAddress != 0) TerrainReceiverMeshes.remove(flywheel$receiverAddress, flywheel$receivers);
    }
}
