package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.IndexSequence;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

public final class IndexMesh implements Mesh {
    private final Mesh source;
    private final int[] indices;
    private final IndexSequence sequence;

    public IndexMesh(Mesh source, int[] indices) {
        this(source, indices, true);
    }

    private IndexMesh(Mesh source, int[] indices, boolean copy) {
        this.source = source.vertexSource();
        this.indices = copy ? indices.clone() : indices;
        if (indices.length % 3 != 0) throw new IllegalArgumentException("Triangle index count: " + indices.length);
        for (int index : indices) {
            if (index < 0 || index >= source.vertexCount())
                throw new IllegalArgumentException("Vertex index: " + index);
        }
        sequence = (ptr, count) -> {
            if (count != this.indices.length) throw new IllegalArgumentException("Index count changed: " + count);
            for (int i = 0; i < count; i++) MemoryUtil.memPutInt(ptr + (long) i * Integer.BYTES, this.indices[i]);
        };
    }

    /**
     * Takes a freshly allocated index array that the caller will not reuse.
     */
    static IndexMesh takeOwnership(Mesh source, int[] indices) {
        return new IndexMesh(source, indices, false);
    }

    @Override
    public int vertexCount() {
        return source.vertexCount();
    }

    @Override
    public void write(MutableVertexList vertices) {
        source.write(vertices);
    }

    @Override
    public IndexSequence indexSequence() {
        return sequence;
    }

    @Override
    public int indexCount() {
        return indices.length;
    }

    @Override
    public Vector4fc boundingSphere() {
        return source.boundingSphere();
    }

    @Override
    public Mesh vertexSource() {
        return source;
    }
}
