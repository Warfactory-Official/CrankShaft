package dev.engine_room.flywheel.backend.mixin.mod.celeritas;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.engine.NativeChunkOit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class NativeChunkDrawMixin {

    // Preserve the renderer's native format, command stream and region uniforms in each OIT pass.
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lorg/embeddedt/embeddium/impl/gl/device/MultiDrawBatch;execute(Lorg/embeddedt/embeddium/impl/gl/device/CommandList;Lorg/embeddedt/embeddium/impl/gl/tessellation/GlTessellation;Lorg/embeddedt/embeddium/impl/gl/tessellation/GlPrimitiveType;)V"), require = 1)
    private void flw$draw(@Coerce Object emitter, @Coerce Object commands, @Coerce Object tessellation, @Coerce Object primitive, Operation<Void> original) {
        var program = NativeChunkOit.bindDraw();
        try { original.call(emitter, commands, tessellation, primitive); }
        finally { NativeChunkOit.restoreDraw(program); }
    }
}
