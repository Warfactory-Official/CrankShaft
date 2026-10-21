package dev.engine_room.flywheel.backend.mixin.mod.nothirium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.engine.NativeChunkOit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "meldexun.nothirium.mc.renderer.chunk.ChunkRendererGL20", remap = false)
public abstract class NativeGL20DrawMixin {

    // Bind after the native renderer has selected each chunk's offset.
    @WrapOperation(method = "renderChunks", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDrawArrays(III)V"), require = 1)
    private void flw$draw(int primitive, int first, int count, Operation<Void> original) {
        var program = NativeChunkOit.bindDraw();
        try { original.call(primitive, first, count); }
        finally { NativeChunkOit.restoreDraw(program); }
    }
}
