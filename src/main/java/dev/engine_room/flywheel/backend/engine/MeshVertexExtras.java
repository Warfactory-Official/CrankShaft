package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.vertex.VertexList;
import dev.engine_room.flywheel.backend.gl.array.VertexAttribute;

import java.util.List;

public interface MeshVertexExtras {
    int stride();

    List<VertexAttribute> attributes();

    boolean prepare();

    void write(Mesh mesh, VertexList vertices, long destination);
}
