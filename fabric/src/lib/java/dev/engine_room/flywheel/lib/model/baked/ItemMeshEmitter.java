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
import net.minecraft.util.LightCoordsUtil;

// Item variant of FabricMeshEmitter: consumes raw BakedQuads with a full display-space transform + a flat per-quad
// tint. Vanilla's (Fabric) BakedQuad has no bakedColors(), so the flat tint is used alone (no baked-shade multiply;
// the NeoForge counterpart multiplies its patched bakedColors()).
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
        normalMatrix.transform(quad.direction().getUnitVec3f(), scratchNormal).normalize();
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

            normals.add(scratchNormal.x);
            normals.add(scratchNormal.y);
            normals.add(scratchNormal.z);

            colors.add(tint);
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
