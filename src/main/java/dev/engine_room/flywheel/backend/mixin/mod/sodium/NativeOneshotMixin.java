package dev.engine_room.flywheel.backend.mixin.mod.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.engine.NativeChunkOit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import java.nio.IntBuffer;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.backends.oneshot.ChunkRenderBackendOneshot", remap = false)
public abstract class NativeOneshotMixin {

    // Keep native per-chunk model offsets and client-side draw lists intact.
    @WrapOperation(method = "drawBatch", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/gl/device/DrawCommandList;multiDrawArrays(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;)V"), require = 1)
    private void flw$draw(@Coerce Object commands, IntBuffer first, IntBuffer count, Operation<Void> original) {
        var program = NativeChunkOit.bindDraw();
        try { original.call(commands, first, count); }
        finally { NativeChunkOit.restoreDraw(program); }
    }
}
