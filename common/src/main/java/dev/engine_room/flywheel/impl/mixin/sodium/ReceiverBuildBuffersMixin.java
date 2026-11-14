package dev.engine_room.flywheel.impl.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverCompilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainReceiverMeshes;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.bsp_tree.UpdatedQuadsList;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

@Mixin(ChunkBuildBuffers.class)
abstract class ReceiverBuildBuffersMixin {
    @Shadow
    @Final
    private ChunkVertexType vertexType;
    @Unique
    private int[] flywheel$mergedReceivers = TerrainReceiverCompilation.EMPTY;
    @Unique
    private long flywheel$mergedAddress;

    @WrapMethod(method = "createMesh")
    private BuiltSectionMeshParts flywheel$meshReceivers(TerrainRenderPass pass, int visibleSlices,
                                                         boolean forceUnassigned,
                                                         boolean sliceReordering,
                                                         Operation<BuiltSectionMeshParts> original) {
        try {
            var mesh = original.call(pass, visibleSlices, forceUnassigned, sliceReordering);
            if (mesh != null) mesh.getVertexData().flywheel$receiverIds(flywheel$mergedReceivers);
            return mesh;
        } finally {
            flywheel$releaseMerge();
        }
    }

    @WrapMethod(method = "createModifiedTranslucentMesh")
    private BuiltSectionMeshParts flywheel$splitReceivers(UpdatedQuadsList updatedQuads,
                                                          Operation<BuiltSectionMeshParts> original) {
        try {
            var mesh = original.call(updatedQuads);
            mesh.getVertexData().flywheel$receiverIds(flywheel$mergedReceivers);
            return mesh;
        } finally {
            flywheel$releaseMerge();
        }
    }

    @Unique
    private void flywheel$releaseMerge() {
        if (flywheel$mergedAddress != 0) TerrainReceiverMeshes.remove(flywheel$mergedAddress, flywheel$mergedReceivers);
        flywheel$mergedAddress = 0;
        flywheel$mergedReceivers = TerrainReceiverCompilation.EMPTY;
    }

    @WrapOperation(method = {"createMesh", "createModifiedTranslucentMesh"}, at = @At(value = "INVOKE", target = "Ljava/nio/ByteBuffer;put(Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;"))
    private ByteBuffer flywheel$mergeReceivers(ByteBuffer destination, ByteBuffer source,
                                               Operation<ByteBuffer> original) {
        var ids = TerrainReceiverMeshes.find(source);
        int stride = vertexType.getVertexFormat().getVertexSize();
        if (ids.length != 0) {
            if (flywheel$mergedReceivers.length == 0) {
                flywheel$mergedReceivers = new int[destination.capacity() / stride];
                flywheel$mergedAddress = MemoryUtil.memAddress0(destination);
                TerrainReceiverMeshes.publish(flywheel$mergedAddress, flywheel$mergedReceivers);
            }
            System.arraycopy(ids, 0, flywheel$mergedReceivers, destination.position() / stride, ids.length);
        }
        return original.call(destination, source);
    }
}
