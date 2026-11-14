package dev.engine_room.flywheel.api.model;

import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import org.joml.Vector4fc;

/**
 * A holder for arbitrary vertex data that can be written to memory or a vertex list.
 */
public interface Mesh {
    /**
     * @return The number of vertices this mesh has.
     */
    int vertexCount();

    /**
     * Write this mesh into a vertex list. Vertices with index {@literal <}0 or {@literal >=}{@link #vertexCount()} will not be
     * read or modified.
     *
     * @param vertexList The vertex list to which data is written to.
     */
    void write(MutableVertexList vertexList);

    IndexSequence indexSequence();

    int indexCount();

    /**
     * Vertex storage shared by index-only views. The source must have the same vertices and vertex count;
     * mesh pools retain it while any view is live. Ordinary meshes own their storage.
     */
    default Mesh vertexSource() {
        return this;
    }

    /**
     * Get a vec4 representing this mesh's bounding sphere in the format (x, y, z, radius).
     *
     * @return A vec4 view.
     */
    Vector4fc boundingSphere();
}
