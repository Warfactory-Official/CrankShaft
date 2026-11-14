package dev.engine_room.flywheel.impl.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverCompilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverMesh;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverMeshes;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.quad.FullTQuad;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.Arrays;

@Mixin(FullTQuad.class)
abstract class ReceiverFullQuadMixin implements TerrainReceiverMesh.Quad {
    @Unique
    private int flywheel$receiverId;

    @Inject(method = "fromVertices", at = @At("RETURN"))
    private static void flywheel$capture(ChunkVertexEncoder.Vertex[] vertices, ModelQuadFacing _, int _,
                                         CallbackInfoReturnable<FullTQuad> cir) {
        var context = TerrainReceiverCompilation.current();
        if (context == null || cir.getReturnValue() == null) return;
        var quad = context.quad();
        for (int index = 0; index < 4; index++) {
            var vertex = vertices[index];
            int at = index * 5;
            quad[at] = vertex.x;
            quad[at + 1] = vertex.y;
            quad[at + 2] = vertex.z;
            quad[at + 3] = vertex.u;
            quad[at + 4] = vertex.v;
        }
        cir.getReturnValue().flywheel$receiverId(context.match());
    }

    @Inject(method = "splittingCopy", at = @At("RETURN"))
    private static void flywheel$inherit(FullTQuad source, CallbackInfoReturnable<FullTQuad> cir) {
        cir.getReturnValue().flywheel$receiverId(
                source.flywheel$receiverId());
    }

    @Override
    public int flywheel$receiverId() {
        return flywheel$receiverId;
    }

    @Override
    public void flywheel$receiverId(int id) {
        flywheel$receiverId = id;
    }

    @WrapOperation(method = "writeToBuffer", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;writeExternal(Ljava/nio/ByteBuffer;I[Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V"))
    private void flywheel$writeReceiver(ChunkMeshBufferBuilder builder, ByteBuffer buffer, int position,
                                        ChunkVertexEncoder.Vertex[] vertices, Material material,
                                        Operation<Void> original) {
        original.call(builder, buffer, position, vertices, material);
        var ids = TerrainReceiverMeshes.findBase(buffer);
        if (ids.length != 0) Arrays.fill(ids, position, position + 4, flywheel$receiverId);
    }
}
