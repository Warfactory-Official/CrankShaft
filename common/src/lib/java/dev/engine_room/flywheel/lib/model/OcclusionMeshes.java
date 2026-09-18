package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.lighting.OcclusionMesh;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.lib.vertex.DefaultVertexList;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * Captures immutable triangle geometry from a render mesh without creating a render instance.
 * Call during model preparation on either loader; reuse the returned mesh for posed occluders.
 */
public final class OcclusionMeshes {
    private OcclusionMeshes() {
    }

    public static OcclusionMesh opaque(Mesh mesh) {
        return capture(mesh, null);
    }

    public static OcclusionMesh cutout(Mesh mesh, OcclusionMesh.Cutout coverage) {
        return capture(mesh, coverage);
    }

    private static OcclusionMesh capture(Mesh mesh, OcclusionMesh.@Nullable Cutout coverage) {
        float[] positions = new float[Math.multiplyExact(mesh.vertexCount(), 3)];
        float[] uv = coverage == null ? null : new float[Math.multiplyExact(mesh.vertexCount(), 2)];
        float[] alpha = coverage == null ? null : new float[mesh.vertexCount()];
        if (alpha != null) Arrays.fill(alpha, 1);
        mesh.write(new DefaultVertexList() {
            @Override
            public int vertexCount() {
                return mesh.vertexCount();
            }

            @Override
            public void x(int index, float value) {
                positions[index * 3] = value;
            }

            @Override
            public void y(int index, float value) {
                positions[index * 3 + 1] = value;
            }

            @Override
            public void z(int index, float value) {
                positions[index * 3 + 2] = value;
            }

            @Override
            public void u(int index, float value) {
                if (uv != null) uv[index * 2] = value;
            }

            @Override
            public void v(int index, float value) {
                if (uv != null) uv[index * 2 + 1] = value;
            }

            @Override
            public void a(int index, float value) {
                if (alpha != null) alpha[index] = value;
            }
        });
        var buffer = MemoryUtil.memAllocInt(mesh.indexCount());
        try {
            mesh.indexSequence().fill(MemoryUtil.memAddress(buffer), mesh.indexCount());
            int[] indices = new int[mesh.indexCount()];
            buffer.get(indices);
            return new OcclusionMesh(positions, indices, uv, alpha, coverage);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
}
