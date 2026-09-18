package dev.engine_room.flywheel.backend.lighting;

import dev.engine_room.flywheel.api.lighting.OcclusionMesh;
import it.unimi.dsi.fastutil.ints.IntArrayList;

final class GeometryAoMesh {
    final int[] nodes;
    final int[] triangles;
    final OcclusionMesh.Cutout cutout;
    int references;
    int nodeOffset;
    int triangleOffset;
    int cutoutOffset;

    GeometryAoMesh(OcclusionMesh mesh) {
        cutout = mesh.cutout();
        int stride = cutout == null ? 9 : 18;
        triangles = new int[mesh.triangleCount() * stride];
        float[] bounds = new float[mesh.triangleCount() * 6];
        for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
            for (int axis = 0; axis < 3; axis++) {
                float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
                for (int corner = 0; corner < 3; corner++) {
                    float coordinate = mesh.coordinate(mesh.index(triangle, corner), axis);
                    triangles[triangle * stride + corner * 3 + axis] = Float.floatToRawIntBits(coordinate);
                    min = Math.min(min, coordinate);
                    max = Math.max(max, coordinate);
                }
                bounds[triangle * 6 + axis] = min;
                bounds[triangle * 6 + axis + 3] = max;
            }
            if (cutout != null) for (int corner = 0; corner < 3; corner++) {
                int vertex = mesh.index(triangle, corner);
                int at = triangle * stride + 9 + corner * 3;
                triangles[at] = Float.floatToRawIntBits(mesh.uv(vertex, 0));
                triangles[at + 1] = Float.floatToRawIntBits(mesh.uv(vertex, 1));
                triangles[at + 2] = Float.floatToRawIntBits(mesh.alpha(vertex));
            }
        }
        nodes = OcclusionBvh.build(bounds);
    }

    void append(IntArrayList data) {
        nodeOffset = data.size();
        data.addElements(data.size(), nodes);
        triangleOffset = data.size();
        data.addElements(data.size(), triangles);
    }
}
