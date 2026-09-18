package dev.engine_room.flywheel.backend.engine;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.vertex.VertexList;

/**
 * A second per-vertex stream beside {@link MeshPool}'s vertices (vertex binding 1), for shaderpack guests. Installed
 * with {@link MeshPool#extras}; written on the render thread during pool flushes.
 */
public interface MeshVertexExtras {
    VertexFormat format();

    /**
     * Writes {@code format().getVertexSize()} bytes per vertex of {@code mesh} at {@code ptr}; {@code vertices} reads
     * the mesh's pooled vertices.
     */
    void write(Mesh mesh, VertexList vertices, long ptr);
}
