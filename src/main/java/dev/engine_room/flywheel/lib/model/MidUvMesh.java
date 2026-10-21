package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.Mesh;

/**
 * Implement alongside Mesh to supply sprite-center UVs to the shader backend.
 * The render thread calls writeMidUv during arena upload; the destination is borrowed for that call.
 */
public interface MidUvMesh {
    /** 8 bytes per vertex (2× float). */
    int MID_UV_BYTES_PER_VERTEX = 8;

    /**
     * Copy this mesh's per-vertex mid-UV (2× float) into {@code destPtr}, writing
     * {@code vertexCount() * MID_UV_BYTES_PER_VERTEX} bytes total.
     */
    void writeMidUv(long destPtr);
}
