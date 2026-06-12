package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.Model;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.List;

public class SimpleModel implements Model {
    private final List<ConfiguredMesh> meshes;
    private final Vector4fc boundingSphere;

    public SimpleModel(List<ConfiguredMesh> meshes) {
        this.meshes = List.copyOf(meshes);
        this.boundingSphere = encloseAll(this.meshes);
    }

    private static Vector4fc encloseAll(List<ConfiguredMesh> meshes) {
        if (meshes.isEmpty()) {
            return new Vector4f(0, 0, 0, 0);
        }
        Vector4f result = new Vector4f(meshes.get(0)
                                             .mesh()
                                             .boundingSphere());
        for (int i = 1; i < meshes.size(); i++) {
            enclose(result, meshes.get(i)
                                  .mesh()
                                  .boundingSphere());
        }
        return result;
    }

    private static void enclose(Vector4f a, Vector4fc b) {
        float dx = b.x() - a.x, dy = b.y() - a.y, dz = b.z() - a.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist + b.w() <= a.w) {
            return; // b already inside a
        }
        if (dist + a.w <= b.w()) {
            a.set(b); // a inside b
            return;
        }
        float newRadius = (dist + a.w + b.w()) * 0.5f;
        float t = (newRadius - a.w) / dist;
        a.x += dx * t;
        a.y += dy * t;
        a.z += dz * t;
        a.w = newRadius;
    }

    @Override
    public List<ConfiguredMesh> meshes() {
        return meshes;
    }

    @Override
    public Vector4fc boundingSphere() {
        return boundingSphere;
    }
}
