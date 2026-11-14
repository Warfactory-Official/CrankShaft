package dev.engine_room.flywheel.impl.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverCompilation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BufferBuilder.class)
abstract class BufferBuilderReceiverMixin {
    @Inject(method = "build", at = @At("RETURN"))
    private void flywheel$captureReceivers(CallbackInfoReturnable<MeshData> cir) {
        var context = TerrainReceiverCompilation.current();
        var mesh = cir.getReturnValue();
        if (context == null || mesh == null) return;
        var format = mesh.drawState().format();
        var position = format.getElement("Position");
        var uv = format.getElement("UV0");
        if (position == null || uv == null) return;
        mesh.flywheel$receiverIds(
                context.capture(mesh.vertexBuffer(), format.getVertexSize(), position.offset(), uv.offset()));
    }
}
