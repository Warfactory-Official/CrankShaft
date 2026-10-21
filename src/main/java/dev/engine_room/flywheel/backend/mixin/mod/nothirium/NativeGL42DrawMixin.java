package dev.engine_room.flywheel.backend.mixin.mod.nothirium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.engine.NativeChunkOit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.ChunkRendererGL42", remap = false)
public abstract class NativeGL42DrawMixin {

    // Bind after the native renderer has selected each chunk's offset.
    @WrapOperation(method = "renderChunks", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL42;glDrawArraysInstancedBaseInstance(IIIII)V"), require = 1)
    private void flw$draw(int primitive, int first, int count, int instances, int base, Operation<Void> original) {
        var program = NativeChunkOit.bindDraw();
        try { original.call(primitive, first, count, instances, base); }
        finally { NativeChunkOit.restoreDraw(program); }
    }
}
