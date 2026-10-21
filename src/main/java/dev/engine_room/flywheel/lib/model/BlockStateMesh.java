package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.Mesh;
import net.minecraft.block.state.IBlockState;
import org.jspecify.annotations.Nullable;

/**
 * A mesh retaining its baked state and transformed block centre for shaderpack materials. Implement this on
 * custom block meshes; metadata is read during render-thread upload and must remain stable until the mesh is rebuilt.
 */
public interface BlockStateMesh extends Mesh {
    static @Nullable BlockStateMesh find(Mesh mesh) {
        while (mesh instanceof RetexturedMesh retextured) mesh = retextured.mesh();
        return mesh instanceof BlockStateMesh block ? block : null;
    }

    @Nullable IBlockState blockState(int vertex);

    float blockCenterX(int vertex);

    float blockCenterY(int vertex);

    float blockCenterZ(int vertex);
}
