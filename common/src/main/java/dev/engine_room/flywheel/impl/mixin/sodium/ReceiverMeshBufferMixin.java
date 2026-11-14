package dev.engine_room.flywheel.impl.mixin.sodium;

import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverCompilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverMeshes;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.nio.ByteBuffer;

import java.util.Arrays;

@Mixin(ChunkMeshBufferBuilder.class)
abstract class ReceiverMeshBufferMixin {
    @Shadow
    private int vertexCount;
    @Unique
    private int[] flywheel$receivers = TerrainReceiverCompilation.EMPTY;
    @Unique
    private int[] flywheel$published;
    @Unique
    private long flywheel$address;
    @Unique
    private TerrainReceiverCompilation.@Nullable Context flywheel$receiverContext;

    @Inject(method = "start", at = @At("HEAD"))
    private void flywheel$resetReceivers(int _, CallbackInfo _) {
        if (flywheel$address != 0) TerrainReceiverMeshes.remove(flywheel$address, flywheel$published);
        flywheel$address = 0;
        flywheel$receivers = TerrainReceiverCompilation.EMPTY;
        flywheel$receiverContext = TerrainReceiverCompilation.current();
    }

    @Inject(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V", at = @At("HEAD"))
    private void flywheel$matchReceiver(ChunkVertexEncoder.Vertex[] vertices, int _, CallbackInfo _) {
        var context = flywheel$receiverContext;
        if (context == null) return;
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
        int id = context.match();
        if (id == 0 && flywheel$receivers.length == 0) return;
        if (vertexCount + 4 > flywheel$receivers.length)
            flywheel$receivers = Arrays.copyOf(flywheel$receivers,
                    Math.max(vertexCount + 4, flywheel$receivers.length * 2));
        Arrays.fill(flywheel$receivers, vertexCount, vertexCount + 4, id);
    }

    @Inject(method = "slice", at = @At("RETURN"))
    private void flywheel$publishReceivers(CallbackInfoReturnable<ByteBuffer> cir) {
        if (flywheel$receivers.length == 0) return;
        if (flywheel$address != 0) TerrainReceiverMeshes.remove(flywheel$address, flywheel$published);
        flywheel$address = MemoryUtil.memAddress(cir.getReturnValue());
        flywheel$published = Arrays.copyOf(flywheel$receivers, vertexCount);
        TerrainReceiverMeshes.publish(flywheel$address, flywheel$published);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void flywheel$retireReceivers(CallbackInfo _) {
        if (flywheel$address != 0) TerrainReceiverMeshes.remove(flywheel$address, flywheel$published);
    }
}
