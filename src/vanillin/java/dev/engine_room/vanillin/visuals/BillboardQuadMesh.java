package dev.engine_room.vanillin.visuals;

import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.lib.model.QuadMesh;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/** The vanilla sprite-entity quad shared by {@code RenderXPOrb}, {@code RenderFireball} and
 *  {@code RenderDragonFireball}: x -0.5..0.5, y -0.25..0.75, full-texture UV. Pair with
 *  {@code InstanceTypes.BILLBOARD}; select a sprite via the per-instance uvRegion. */
final class BillboardQuadMesh implements QuadMesh {
    static final BillboardQuadMesh INSTANCE = new BillboardQuadMesh();
    private static final Vector4fc BOUNDING_SPHERE = new Vector4f(0, 0.25F, 0, (float) Math.sqrt(0.5));

    private BillboardQuadMesh() {
    }

    @Override
    public int vertexCount() {
        return 4;
    }

    @Override
    public void write(MutableVertexList vertexList) {
        writeVertex(vertexList, 0, -0.5F, -0.25F, 0, 1);
        writeVertex(vertexList, 1, 0.5F, -0.25F, 1, 1);
        writeVertex(vertexList, 2, 0.5F, 0.75F, 1, 0);
        writeVertex(vertexList, 3, -0.5F, 0.75F, 0, 0);
    }

    private static void writeVertex(MutableVertexList vertexList, int i, float x, float y, float u, float v) {
        vertexList.x(i, x);
        vertexList.y(i, y);
        vertexList.z(i, 0);
        vertexList.r(i, 1);
        vertexList.g(i, 1);
        vertexList.b(i, 1);
        vertexList.a(i, 1);
        vertexList.u(i, u);
        vertexList.v(i, v);
        vertexList.light(i, 0);
        vertexList.overlay(i, OverlayTexture.NO_OVERLAY);
        vertexList.normalX(i, 0);
        vertexList.normalY(i, 1);
        vertexList.normalZ(i, 0);
    }

    @Override
    public Vector4fc boundingSphere() {
        return BOUNDING_SPHERE;
    }
}
