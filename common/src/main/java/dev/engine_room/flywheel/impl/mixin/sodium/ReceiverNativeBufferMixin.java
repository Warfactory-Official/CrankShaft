package dev.engine_room.flywheel.impl.mixin.sodium;

import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverCompilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverMesh;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverMeshes;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

@Mixin(NativeBuffer.class)
abstract class ReceiverNativeBufferMixin implements TerrainReceiverMesh {
    @Unique
    private int[] flywheel$receivers = TerrainReceiverCompilation.EMPTY;
    @Unique
    private long flywheel$address;

    @Override
    public int[] flywheel$receiverIds() {
        return flywheel$receivers;
    }

    @Override
    public void flywheel$receiverIds(int[] ids) {
        flywheel$receivers = ids;
    }

    @Inject(method = "getDirectBuffer", at = @At("RETURN"))
    private void flywheel$publishReceivers(CallbackInfoReturnable<ByteBuffer> cir) {
        if (flywheel$receivers.length == 0) return;
        flywheel$address = MemoryUtil.memAddress(cir.getReturnValue());
        TerrainReceiverMeshes.publish(flywheel$address, flywheel$receivers);
    }

    @Inject(method = "free", at = @At("HEAD"))
    private void flywheel$retireReceivers(CallbackInfo _) {
        if (flywheel$address != 0) TerrainReceiverMeshes.remove(flywheel$address, flywheel$receivers);
    }
}
