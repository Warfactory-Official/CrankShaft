package dev.engine_room.flywheel.lib.model.baked;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Fabric's renderer API emits the display path's geometry into a mesh ({@code BlockStateModelWrapper.update}
 * overwritten) and submits it through its overload; the default forward would drop the mesh.
 */
final class FabricDisplayBlockCapture extends DisplayBlockCapture {
    final List<MeshSubmit> meshes = new ArrayList<>();

    @Override
    public void submitBlockModel(PoseStack poseStack, Function<ChunkSectionLayer, RenderType> renderTypeFunction,
                                 boolean translucent, List<BlockStateModelPart> parts, @Nullable Mesh mesh,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        meshes.add(new MeshSubmit(poseStack.last().copy(), renderTypeFunction.apply(ChunkSectionLayer.SOLID),
                List.copyOf(parts), mesh, tintLayers.clone()));
    }

    record MeshSubmit(PoseStack.Pose pose, RenderType renderType, List<BlockStateModelPart> parts,
                      @Nullable Mesh mesh, int[] tints) {
    }
}
