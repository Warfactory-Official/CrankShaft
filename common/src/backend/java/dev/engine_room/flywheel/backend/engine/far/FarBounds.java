package dev.engine_room.flywheel.backend.engine.far;

import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.vertex.PosVertexView;

public final class FarBounds {
    private FarBounds() {
    }

    /**
     * Grows model-space bounds by the vertices produced by the mesh.
     */
    public static void growBounds(Mesh mesh, float[] min, float[] max) {
        var block = MemoryBlock.malloc((long) mesh.vertexCount() * PosVertexView.STRIDE);
        try {
            var vertices = new PosVertexView();
            vertices.ptr(block.ptr());
            vertices.vertexCount(mesh.vertexCount());
            mesh.write(vertices);
            for (int i = 0; i < mesh.vertexCount(); i++) {
                min[0] = Math.min(min[0], vertices.x(i));
                min[1] = Math.min(min[1], vertices.y(i));
                min[2] = Math.min(min[2], vertices.z(i));
                max[0] = Math.max(max[0], vertices.x(i));
                max[1] = Math.max(max[1], vertices.y(i));
                max[2] = Math.max(max[2], vertices.z(i));
            }
        } finally {
            block.free();
        }
    }
}
