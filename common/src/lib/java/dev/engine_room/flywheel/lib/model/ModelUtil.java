package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.model.Model.ConfiguredMesh;
import dev.engine_room.flywheel.api.vertex.VertexList;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.baked.BakedMesh;
import dev.engine_room.flywheel.lib.model.baked.BlockMaterialFunction;
import dev.engine_room.flywheel.lib.vertex.PosVertexView;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;

/**
 * Model geometry helpers + the vanilla-content material table; 26.2 bakes shade/AO into vertex colours, so every layer maps to a single UNSHADED material.
 */
public final class ModelUtil {
    public static final float BOUNDING_SPHERE_EPSILON = 1e-4f;

    private static final Material[] CHUNK_MATERIALS = new Material[ChunkSectionLayer.values().length];
    private static final Material[] ITEM_MATERIALS = new Material[ChunkSectionLayer.values().length];
    private static final Material[] BLOCK_ITEM_MATERIALS = new Material[ChunkSectionLayer.values().length];

    static {
        CHUNK_MATERIALS[ChunkSectionLayer.SOLID.ordinal()] = Materials.SOLID_UNSHADED_BLOCK;
        // 26.2 folds cutout + cutout_mipped into the single mipped CUTOUT_TERRAIN pipeline.
        CHUNK_MATERIALS[ChunkSectionLayer.CUTOUT.ordinal()] = Materials.CUTOUT_MIPPED_UNSHADED_BLOCK;
        CHUNK_MATERIALS[ChunkSectionLayer.TRANSLUCENT.ordinal()] = Materials.TRANSLUCENT_UNSHADED_BLOCK;

        ITEM_MATERIALS[ChunkSectionLayer.SOLID.ordinal()] = Materials.SOLID_ITEM;
        ITEM_MATERIALS[ChunkSectionLayer.CUTOUT.ordinal()] = Materials.CUTOUT_ITEM;
        ITEM_MATERIALS[ChunkSectionLayer.TRANSLUCENT.ordinal()] = Materials.TRANSLUCENT_ITEM;

        BLOCK_ITEM_MATERIALS[ChunkSectionLayer.SOLID.ordinal()] = Materials.SOLID_BLOCK_ITEM;
        BLOCK_ITEM_MATERIALS[ChunkSectionLayer.CUTOUT.ordinal()] = Materials.CUTOUT_BLOCK_ITEM;
        BLOCK_ITEM_MATERIALS[ChunkSectionLayer.TRANSLUCENT.ordinal()] = Materials.TRANSLUCENT_BLOCK_ITEM;
    }

    private ModelUtil() {
    }

    @Nullable
    public static Material getMaterial(ChunkSectionLayer layer) {
        return CHUNK_MATERIALS[layer.ordinal()];
    }

    /**
     * The flywheel material a baked ITEM quad of the given chunk layer draws with, or {@code null}.
     */
    @Nullable
    public static Material getItemMaterial(ChunkSectionLayer layer) {
        return getItemMaterial(layer, true);
    }

    @Nullable
    public static Material getItemMaterial(ChunkSectionLayer layer, boolean blocksAtlas) {
        return (blocksAtlas ? BLOCK_ITEM_MATERIALS : ITEM_MATERIALS)[layer.ordinal()];
    }

    public static Model buildModel(EnumMap<ChunkSectionLayer, BakedMesh> meshes, BlockMaterialFunction materialFunc) {
        List<ConfiguredMesh> result = new ArrayList<>(meshes.size());
        for (var entry : meshes.entrySet()) {
            Material material = materialFunc.apply(entry.getKey());
            if (material != null) {
                result.add(new ConfiguredMesh(material, entry.getValue()));
            }
        }
        if (result.isEmpty()) {
            return EmptyModel.INSTANCE;
        }
        return new SimpleModel(result);
    }

    public static int computeTotalVertexCount(Iterable<Mesh> meshes) {
        int vertexCount = 0;
        for (Mesh mesh : meshes) {
            vertexCount += mesh.vertexCount();
        }
        return vertexCount;
    }

    public static Vector4f computeBoundingSphere(Collection<ConfiguredMesh> meshes) {
        return computeBoundingSphere(meshes.stream().map(ConfiguredMesh::mesh).toList());
    }

    public static Vector4f computeBoundingSphere(Iterable<Mesh> meshes) {
        int vertexCount = computeTotalVertexCount(meshes);
        var block = MemoryBlock.malloc((long) vertexCount * PosVertexView.STRIDE);
        var vertexList = new PosVertexView();

        int baseVertex = 0;
        for (Mesh mesh : meshes) {
            vertexList.ptr(block.ptr() + (long) baseVertex * PosVertexView.STRIDE);
            vertexList.vertexCount(mesh.vertexCount());
            mesh.write(vertexList);
            baseVertex += mesh.vertexCount();
        }

        vertexList.ptr(block.ptr());
        vertexList.vertexCount(vertexCount);
        var sphere = computeBoundingSphere(vertexList);

        block.free();

        return sphere;
    }

    public static Vector4f computeBoundingSphere(VertexList vertexList) {
        var center = computeCenterOfAABBContaining(vertexList);

        var radius = computeMaxDistanceTo(vertexList, center) + BOUNDING_SPHERE_EPSILON;

        return new Vector4f(center, radius);
    }

    private static float computeMaxDistanceTo(VertexList vertexList, Vector3f pos) {
        float farthestDistanceSquared = -1;

        for (int i = 0; i < vertexList.vertexCount(); i++) {
            var distanceSquared = pos.distanceSquared(vertexList.x(i), vertexList.y(i), vertexList.z(i));

            if (distanceSquared > farthestDistanceSquared) {
                farthestDistanceSquared = distanceSquared;
            }
        }

        return (float) Math.sqrt(farthestDistanceSquared);
    }

    private static Vector3f computeCenterOfAABBContaining(VertexList vertexList) {
        var min = new Vector3f(Float.MAX_VALUE);
        // -Float.MAX_VALUE, NOT Float.MIN_VALUE (the smallest positive float): an all-negative axis never lowers the max.
        var max = new Vector3f(-Float.MAX_VALUE);

        for (int i = 0; i < vertexList.vertexCount(); i++) {
            float x = vertexList.x(i);
            float y = vertexList.y(i);
            float z = vertexList.z(i);

            // JOML's min/max methods don't accept floats :whywheel:
            min.x = Math.min(min.x, x);
            min.y = Math.min(min.y, y);
            min.z = Math.min(min.z, z);

            max.x = Math.max(max.x, x);
            max.y = Math.max(max.y, y);
            max.z = Math.max(max.z, z);
        }

        return min.add(max)
                  .mul(0.5f);
    }
}
