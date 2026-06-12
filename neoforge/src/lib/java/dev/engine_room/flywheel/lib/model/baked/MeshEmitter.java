package dev.engine_room.flywheel.lib.model.baked;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.QuadInstance;

import dev.engine_room.flywheel.lib.model.baked.BakedMesh;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.ARGB;
import net.neoforged.neoforge.client.model.quad.BakedNormals;

// NeoForge patches bakedColors + bakedNormals directly onto the vanilla 26.2 BakedQuad record.
final class MeshEmitter {
    private final FloatArrayList positions = new FloatArrayList();
    private final FloatArrayList uvs = new FloatArrayList();
    private final FloatArrayList normals = new FloatArrayList();
    private final IntArrayList colors = new IntArrayList();
    private final IntArrayList overlays = new IntArrayList();

    // Non-null for standalone/partial bakes carrying a PoseStack transform; null for the plain block bake.
    @Nullable
    private final Matrix4fc pose;
    @Nullable
    private final Matrix3fc normalMatrix;
    private final Vector3f scratchPos = new Vector3f();
    private final Vector3f scratchNormal = new Vector3f();

    MeshEmitter() {
        this(null, null);
    }

    MeshEmitter(@Nullable Matrix4fc pose, @Nullable Matrix3fc normalMatrix) {
        this.pose = pose;
        this.normalMatrix = normalMatrix;
    }

    void accept(float ox, float oy, float oz, BakedQuad quad, QuadInstance instance) {
        // Flat face-direction normal, used as the fallback where a vertex leaves its baked normal unspecified.
        Vector3fc faceNormal = quad.direction().getUnitVec3f();
        float fnx;
        float fny;
        float fnz;
        if (normalMatrix != null) {
            normalMatrix.transform(faceNormal, scratchNormal).normalize();
            fnx = scratchNormal.x;
            fny = scratchNormal.y;
            fnz = scratchNormal.z;
        } else {
            fnx = faceNormal.x();
            fny = faceNormal.y();
            fnz = faceNormal.z();
        }

        // Honor NeoForge's per-vertex baked normals (like vanilla applyBakedNormals), falling back to the flat
        // face normal when unspecified; vanilla only runs applyBakedNormals on the POSED path, so the plain
        // block bake (normalMatrix == null) always emits the flat face normal.
        BakedNormals bakedNormals = quad.bakedNormals();

        for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
            Vector3fc pos = quad.position(vertex);
            long packedUv = quad.packedUV(vertex);

            if (pose != null) {
                pose.transformPosition(pos.x() + ox, pos.y() + oy, pos.z() + oz, scratchPos);
                positions.add(scratchPos.x);
                positions.add(scratchPos.y);
                positions.add(scratchPos.z);
            } else {
                positions.add(pos.x() + ox);
                positions.add(pos.y() + oy);
                positions.add(pos.z() + oz);
            }

            uvs.add(UVPair.unpackU(packedUv));
            uvs.add(UVPair.unpackV(packedUv));

            int packedNormal;
            if (normalMatrix == null || BakedNormals.isUnspecified(packedNormal = bakedNormals.normal(vertex))) {
                normals.add(fnx);
                normals.add(fny);
                normals.add(fnz);
            } else {
                scratchNormal.set(BakedNormals.unpackX(packedNormal), BakedNormals.unpackY(packedNormal), BakedNormals.unpackZ(packedNormal));
                normalMatrix.transform(scratchNormal);
                scratchNormal.normalize();
                normals.add(scratchNormal.x);
                normals.add(scratchNormal.y);
                normals.add(scratchNormal.z);
            }

            int bakedColor = quad.bakedColors().color(vertex);
            colors.add(ARGB.multiply(instance.getColor(vertex), bakedColor));
            overlays.add(instance.overlayCoords());
        }
    }

    boolean isEmpty() {
        return positions.isEmpty();
    }

    BakedMesh build() {
        return new BakedMesh(positions.toFloatArray(), uvs.toFloatArray(), normals.toFloatArray(),
                colors.toIntArray(), overlays.toIntArray());
    }
}
