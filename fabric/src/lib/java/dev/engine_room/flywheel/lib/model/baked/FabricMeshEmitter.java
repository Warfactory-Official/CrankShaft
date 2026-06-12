package dev.engine_room.flywheel.lib.model.baked;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import dev.engine_room.flywheel.lib.model.baked.BakedMesh;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;

final class FabricMeshEmitter {
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

    FabricMeshEmitter() {
        this(null, null);
    }

    FabricMeshEmitter(@Nullable Matrix4fc pose, @Nullable Matrix3fc normalMatrix) {
        this.pose = pose;
        this.normalMatrix = normalMatrix;
    }

    void accept(MutableQuadView quad) {
        for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
            if (pose != null) {
                pose.transformPosition(quad.x(vertex), quad.y(vertex), quad.z(vertex), scratchPos);
                positions.add(scratchPos.x);
                positions.add(scratchPos.y);
                positions.add(scratchPos.z);
            } else {
                positions.add(quad.x(vertex));
                positions.add(quad.y(vertex));
                positions.add(quad.z(vertex));
            }

            uvs.add(quad.u(vertex));
            uvs.add(quad.v(vertex));

            float nx;
            float ny;
            float nz;
            if (quad.hasNormal(vertex)) {
                nx = quad.normalX(vertex);
                ny = quad.normalY(vertex);
                nz = quad.normalZ(vertex);
            } else {
                nx = quad.faceNormal().x();
                ny = quad.faceNormal().y();
                nz = quad.faceNormal().z();
            }
            if (normalMatrix != null) {
                normalMatrix.transform(nx, ny, nz, scratchNormal).normalize();
                nx = scratchNormal.x;
                ny = scratchNormal.y;
                nz = scratchNormal.z;
            }
            normals.add(nx);
            normals.add(ny);
            normals.add(nz);

            colors.add(quad.color(vertex));
            // Standalone bakes don't have block entities to provide an overlay.
            overlays.add(OverlayTexture.NO_OVERLAY);
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
