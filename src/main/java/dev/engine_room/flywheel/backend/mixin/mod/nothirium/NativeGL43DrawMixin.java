package dev.engine_room.flywheel.backend.mixin.mod.nothirium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.engine.NativeChunkOit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.ChunkRendererGL43", remap = false)
public abstract class NativeGL43DrawMixin {

    // The native command buffer already contains the visible chunks and their camera-relative offsets.
    @WrapOperation(method = "renderChunks", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL43;glMultiDrawArraysIndirect(IJII)V"), require = 1)
    private void flw$draw(int primitive, long offset, int count, int stride, Operation<Void> original) {
        var program = NativeChunkOit.bindDraw();
        try { original.call(primitive, offset, count, stride); }
        finally { NativeChunkOit.restoreDraw(program); }
    }
}
