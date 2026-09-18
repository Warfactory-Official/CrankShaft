package dev.engine_room.flywheel.backend.engine.instancing;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.engine.DrawTags;
import dev.engine_room.flywheel.backend.engine.GroupKey;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import org.jspecify.annotations.Nullable;

public class InstancedDraw {
    public final GroupKey<?> groupKey;
    private final InstancedInstancer<?> instancer;
    private final MeshPool.PooledMesh mesh;
    private final Material material;
    private final int bias;
    private final int indexOfMeshInModel;
    private final @Nullable DrawTags tags;

    private boolean deleted;

    public InstancedDraw(InstancedInstancer<?> instancer, MeshPool.PooledMesh mesh, GroupKey<?> groupKey,
                         Material material, int bias, int indexOfMeshInModel, @Nullable DrawTags tags) {
        this.instancer = instancer;
        this.mesh = mesh;
        this.groupKey = groupKey;
        this.material = material;
        this.bias = bias;
        this.indexOfMeshInModel = indexOfMeshInModel;
        this.tags = tags;

        mesh.acquire();
    }

    public int bias() {
        return bias;
    }

    public int indexOfMeshInModel() {
        return indexOfMeshInModel;
    }

    public Material material() {
        return material;
    }

    public @Nullable DrawTags tags() {
        return tags;
    }

    public boolean deleted() {
        return deleted;
    }

    public MeshPool.PooledMesh mesh() {
        return mesh;
    }

    public InstancedInstancer<?> instancer() {
        return instancer;
    }

    public void delete() {
        if (deleted) {
            return;
        }

        mesh.release();

        deleted = true;
    }
}
