package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.vertex.VertexList;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.baked.BlockMaterialFunction;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

public final class ModelUtil {
    private static final float BOUNDING_SPHERE_EPSILON = 1e-4f;

    /** The six facings plus a trailing {@code null} for face-less quads. Shared by every
     *  {@code IBakedModel.getQuads}-driven baker. */
    public static final @Nullable EnumFacing[] DIRECTIONS = {
            EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH,
            EnumFacing.WEST, EnumFacing.EAST, null
    };

    public static final BlockRenderLayer[] LAYERS = BlockRenderLayer.values();

    /**
     * 16-entry (layer × shaded × ao) Material table, indexed by
     * {@code layer.ordinal()*4 + (shaded?2:0) + (ao?1:0)}. Mirrors upstream Flywheel's
     * {@code CHUNK_MATERIALS}.
     *
     * <p><b>Routing invariant:</b> both bits are gated <i>purely in the shader</i>. {@code shaded}
     * picks {@code cardinalLightingMode} (CHUNK vs OFF — diffuse multiply via {@code _flw_diffuseFactor});
     * {@code ao} picks {@code material.ambientOcclusion} (AO multiply via {@code flw_shaderLight} from
     * the runtime {@code flw_light()} sample). The bake path is responsible for NOT pre-multiplying
     * either into vertex color — see
     * {@link dev.engine_room.flywheel.lib.model.baked.LighterPipeline}'s {@code NoBakeLighter}.
     * Pre-baking either would double-apply and visibly darken sides/bottoms.
     */
    private static final Material[] LAYER_MATERIALS = new Material[LAYERS.length * 4];

    static {
        setLayer(BlockRenderLayer.SOLID, Materials.SOLID_BLOCK, Materials.SOLID_UNSHADED_BLOCK);
        setLayer(BlockRenderLayer.CUTOUT_MIPPED, Materials.CUTOUT_MIPPED_BLOCK, Materials.CUTOUT_MIPPED_UNSHADED_BLOCK);
        setLayer(BlockRenderLayer.CUTOUT, Materials.CUTOUT_BLOCK, Materials.CUTOUT_UNSHADED_BLOCK);
        setLayer(BlockRenderLayer.TRANSLUCENT, Materials.TRANSLUCENT_BLOCK, Materials.TRANSLUCENT_UNSHADED_BLOCK);
    }

    private static void setLayer(BlockRenderLayer layer, Material shaded, Material unshaded) {
        int base = layer.ordinal() * 4;
        LAYER_MATERIALS[base]     = SimpleMaterial.builderOf(unshaded).ambientOcclusion(false).build();
        LAYER_MATERIALS[base + 1] = unshaded;
        LAYER_MATERIALS[base + 2] = SimpleMaterial.builderOf(shaded).ambientOcclusion(false).build();
        LAYER_MATERIALS[base + 3] = shaded;
    }

    private ModelUtil() {
    }

    /**
     * Default {@link BlockMaterialFunction}: route by {@code (layer, shaded, ambientOcclusion)} into
     * the {@link #LAYER_MATERIALS} table. Used as fallback when callers of {@code BakedModelBuilder}
     * / {@code BlockModelBuilder} don't supply their own {@code materialFunc}.
     *
     * <p>Callers must pass the <i>gated</i> AO bit (model AO ∧ not-emissive) — passing the raw
     * {@code model.isAmbientOcclusion(state)} would leave emissive blocks with shader AO applied,
     * which vanilla never does. {@code BakedModelBuilder.useAo} and {@code BlockModelBuilder.useAo}
     * are pre-computed for this.
     */
    @Nullable
    public static Material getMaterial(BlockRenderLayer layer, boolean shaded, boolean ambientOcclusion) {
        return LAYER_MATERIALS[layer.ordinal() * 4 + (shaded ? 2 : 0) + (ambientOcclusion ? 1 : 0)];
    }

    public static int computeTotalVertexCount(Iterable<Mesh> meshes) {
        int vertexCount = 0;
        for (Mesh mesh : meshes) {
            vertexCount += mesh.vertexCount();
        }
        return vertexCount;
    }

    /** Compute bounding sphere from a flat VertexList. Two passes: AABB for center, then
     *  farthest-vertex from center for the tight radius. The AABB-corner radius is up to √3×
     *  looser on elongated meshes — a tight sphere lifts cull-rejection rates back to upstream
     *  parity. */
    public static Vector4f computeBoundingSphere(VertexList vertices) {
        int count = vertices.vertexCount();
        if (count == 0) {
            return new Vector4f(0, 0, 0, BOUNDING_SPHERE_EPSILON);
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            float x = vertices.x(i), y = vertices.y(i), z = vertices.z(i);
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float maxDistSqr = 0f;
        for (int i = 0; i < count; i++) {
            float dx = vertices.x(i) - cx;
            float dy = vertices.y(i) - cy;
            float dz = vertices.z(i) - cz;
            float distSqr = dx * dx + dy * dy + dz * dz;
            if (distSqr > maxDistSqr) maxDistSqr = distSqr;
        }
        float radius = (float) Math.sqrt(maxDistSqr) + BOUNDING_SPHERE_EPSILON;
        return new Vector4f(cx, cy, cz, radius);
    }

    public static Vector4f computeBoundingSphere(Collection<Model.ConfiguredMesh> meshes) {
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        boolean any = false;
        for (Model.ConfiguredMesh entry : meshes) {
            Vector4f bs = new Vector4f();
            bs.set(entry.mesh().boundingSphere());
            float r = bs.w;
            min.x = Math.min(min.x, bs.x - r);
            min.y = Math.min(min.y, bs.y - r);
            min.z = Math.min(min.z, bs.z - r);
            max.x = Math.max(max.x, bs.x + r);
            max.y = Math.max(max.y, bs.y + r);
            max.z = Math.max(max.z, bs.z + r);
            any = true;
        }
        if (!any) {
            return new Vector4f(0, 0, 0, BOUNDING_SPHERE_EPSILON);
        }
        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        float dx = max.x - center.x;
        float dy = max.y - center.y;
        float dz = max.z - center.z;
        float radius = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + BOUNDING_SPHERE_EPSILON;
        return new Vector4f(center, radius);
    }
}
