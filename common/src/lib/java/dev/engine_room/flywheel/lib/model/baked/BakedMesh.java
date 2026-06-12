package dev.engine_room.flywheel.lib.model.baked;

import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.lib.model.ModelUtil;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import net.minecraft.util.ARGB;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

public final class BakedMesh implements QuadMesh {
    private final float[] positions;
    private final float[] uvs;
    private final float[] normals;
    private final int[] colors;
    private final int[] overlays;
    private final int @Nullable [] lights;
    private final int vertexCount;
    private final Vector4f boundingSphere;

    public BakedMesh(float[] positions, float[] uvs, float[] normals, int[] colors, int[] overlays) {
        this(positions, uvs, normals, colors, overlays, null);
    }

    public BakedMesh(float[] positions, float[] uvs, float[] normals, int[] colors, int[] overlays,
                     int @Nullable [] lights) {
        this.positions = positions;
        this.uvs = uvs;
        this.normals = normals;
        this.colors = colors;
        this.overlays = overlays;
        this.lights = lights;
        this.vertexCount = positions.length / 3;
        this.boundingSphere = computeBoundingSphere(positions, vertexCount);
    }

    private static Vector4f computeBoundingSphere(float[] positions, int vertexCount) {
        if (vertexCount == 0) {
            return new Vector4f(0, 0, 0, 0);
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < vertexCount; i++) {
            float x = positions[i * 3], y = positions[i * 3 + 1], z = positions[i * 3 + 2];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
        float r2 = 0.0f;
        for (int i = 0; i < vertexCount; i++) {
            float dx = positions[i * 3] - cx, dy = positions[i * 3 + 1] - cy, dz = positions[i * 3 + 2] - cz;
            r2 = Math.max(r2, dx * dx + dy * dy + dz * dz);
        }
        return new Vector4f(cx, cy, cz, (float) Math.sqrt(r2) + ModelUtil.BOUNDING_SPHERE_EPSILON);
    }

    @Override
    public int vertexCount() {
        return vertexCount;
    }

    @Override
    public void write(MutableVertexList dst) {
        for (int i = 0; i < vertexCount; i++) {
            dst.x(i, positions[i * 3]);
            dst.y(i, positions[i * 3 + 1]);
            dst.z(i, positions[i * 3 + 2]);

            int c = colors[i];
            dst.r(i, ARGB.red(c) / 255.0f);
            dst.g(i, ARGB.green(c) / 255.0f);
            dst.b(i, ARGB.blue(c) / 255.0f);
            dst.a(i, ARGB.alpha(c) / 255.0f);

            dst.u(i, uvs[i * 2]);
            dst.v(i, uvs[i * 2 + 1]);

            dst.overlay(i, overlays[i]);
            // Dark by default: the flywheel instance supplies the real lightmap at draw time, and the
            // shader combines mesh + instance light via max(meshLight, instanceLight) -- a full-bright
            // mesh would defeat it. A non-null lights array carries per-quad light emission.
            dst.light(i, lights == null ? 0 : lights[i]);

            dst.normalX(i, normals[i * 3]);
            dst.normalY(i, normals[i * 3 + 1]);
            dst.normalZ(i, normals[i * 3 + 2]);
        }
    }

    @Override
    public Vector4fc boundingSphere() {
        return boundingSphere;
    }
}
