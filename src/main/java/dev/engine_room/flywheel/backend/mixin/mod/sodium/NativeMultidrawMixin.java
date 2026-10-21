package dev.engine_room.flywheel.backend.mixin.mod.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.engine.NativeChunkOit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import java.nio.ByteBuffer;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.backends.multidraw.MultidrawChunkRenderBackend", remap = false)
public abstract class NativeMultidrawMixin {

    // Uniforms and region bindings have been installed before these command submissions.
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/gl/device/DrawCommandList;multiDrawArraysIndirect(JII)V"), require = 1)
    private void flw$gpu(@Coerce Object commands, long offset, int count, int stride, Operation<Void> original) {
        var program = NativeChunkOit.bindDraw();
        try { original.call(commands, offset, count, stride); }
        finally { NativeChunkOit.restoreDraw(program); }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/gl/device/DrawCommandList;multiDrawArraysIndirect(Ljava/nio/ByteBuffer;II)V"), require = 1)
    private void flw$client(@Coerce Object commands, ByteBuffer data, int count, int stride, Operation<Void> original) {
        var program = NativeChunkOit.bindDraw();
        try { original.call(commands, data, count, stride); }
        finally { NativeChunkOit.restoreDraw(program); }
    }
}
