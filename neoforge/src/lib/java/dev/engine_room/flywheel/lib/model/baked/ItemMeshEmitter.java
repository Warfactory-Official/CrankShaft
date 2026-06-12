package dev.engine_room.flywheel.lib.model.baked;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import dev.engine_room.flywheel.lib.model.baked.BakedMesh;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.neoforged.neoforge.client.model.quad.BakedNormals;

// Item variant of MeshEmitter: consumes raw BakedQuads (no QuadInstance) with a full display-space transform + a
// flat per-quad tint. NeoForge multiplies the patched bakedColors() (baked face shade); the Fabric counterpart,
// whose vanilla BakedQuad has no bakedColors(), uses the flat tint alone.
final class ItemMeshEmitter {
    private final FloatArrayList positions = new FloatArrayList();
    private final FloatArrayList uvs = new FloatArrayList();
    private final FloatArrayList normals = new FloatArrayList();
    private final IntArrayList colors = new IntArrayList();
    private final IntArrayList overlays = new IntArrayList();
    private final IntArrayList lights = new IntArrayList();

    private final Vector3f scratchPos = new Vector3f();
    private final Vector3f scratchNormal = new Vector3f();

    void accept(Matrix4fc pose, Matrix3fc normalMatrix, BakedQuad quad, int tint) {
        // Flat face-direction normal, used as the fallback where a vertex leaves its baked normal unspecified.
        normalMatrix.transform(quad.direction().getUnitVec3f(), scratchNormal).normalize();
        float fnx = scratchNormal.x;
        float fny = scratchNormal.y;
        float fnz = scratchNormal.z;

        // 26.2/NeoForge bakes per-vertex (or per-quad) geometric normals onto the BakedQuad; honor them for
        // smooth shading like vanilla VertexConsumer.applyBakedNormals, falling back to the flat face normal.
        BakedNormals bakedNormals = quad.bakedNormals();

        // Per-quad light emission; the vertex shader's max(meshLight, instanceLight) then mirrors vanilla's
        // LightCoordsUtil.lightCoordsWithEmission (max in both halves).
        int emission = quad.materialInfo().lightEmission();
        int light = emission == 0 ? 0 : LightCoordsUtil.pack(emission, emission);

        for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
            pose.transformPosition(quad.position(vertex), scratchPos);
            long packedUv = quad.packedUV(vertex);

            positions.add(scratchPos.x);
            positions.add(scratchPos.y);
            positions.add(scratchPos.z);

            uvs.add(UVPair.unpackU(packedUv));
            uvs.add(UVPair.unpackV(packedUv));

            int packedNormal = bakedNormals.normal(vertex);
            if (BakedNormals.isUnspecified(packedNormal)) {
                normals.add(fnx);
                normals.add(fny);
                normals.add(fnz);
            } else {
                scratchNormal.set(BakedNormals.unpackX(packedNormal), BakedNormals.unpackY(packedNormal), BakedNormals.unpackZ(packedNormal));
                normalMatrix.transform(scratchNormal).normalize();
                normals.add(scratchNormal.x);
                normals.add(scratchNormal.y);
                normals.add(scratchNormal.z);
            }

            colors.add(ARGB.multiply(tint, quad.bakedColors().color(vertex)));
            overlays.add(OverlayTexture.NO_OVERLAY);
            lights.add(light);
        }
    }

    boolean isEmpty() {
        return positions.isEmpty();
    }

    BakedMesh build() {
        return new BakedMesh(positions.toFloatArray(), uvs.toFloatArray(), normals.toFloatArray(),
                colors.toIntArray(), overlays.toIntArray(), lights.toIntArray());
    }
}
