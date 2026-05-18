package dev.engine_room.flywheel.lib.model.baked;

import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.util.OverlayTexture;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 1.12.2: converts a {@code ModelRenderer} to a flywheel {@code Mesh} in the renderer's local
 * frame (no {@code rotationPoint}/{@code rotateAngle} applied). {@code childModels} are NOT
 * recursed.
 */
public final class ModelBaseConverter {
    public static final float DEFAULT_SCALE = 0.0625F;

    private ModelBaseConverter() {
    }

    public static Mesh bake(ModelRenderer renderer) {
        return bake(renderer, DEFAULT_SCALE);
    }

    @Nullable
    public static Mesh bake(ModelRenderer renderer, float scale) {
        int quadCount = 0;
        for (ModelBox box : renderer.cubeList) {
            quadCount += box.quadList.length;
        }
        if (quadCount == 0) return null;

        int vertexCount = quadCount * 4;
        MemoryBlock memoryBlock = MemoryBlock.mallocTracked(vertexCount * FullVertexView.STRIDE);
        FullVertexView meshVertices = new FullVertexView();
        meshVertices.nativeMemoryOwner(memoryBlock);
        meshVertices.ptr(memoryBlock.ptr());
        meshVertices.vertexCount(vertexCount);

        Vector3f edge1 = new Vector3f();
        Vector3f edge2 = new Vector3f();
        Vector3f normal = new Vector3f();

        int vertex = 0;
        for (ModelBox box : renderer.cubeList) {
            for (TexturedQuad quad : box.quadList) {
                PositionTextureVertex[] verts = quad.vertexPositions;

                float v0x = (float) verts[0].vector3D.x * scale;
                float v0y = (float) verts[0].vector3D.y * scale;
                float v0z = (float) verts[0].vector3D.z * scale;
                float v1x = (float) verts[1].vector3D.x * scale;
                float v1y = (float) verts[1].vector3D.y * scale;
                float v1z = (float) verts[1].vector3D.z * scale;
                float v2x = (float) verts[2].vector3D.x * scale;
                float v2y = (float) verts[2].vector3D.y * scale;
                float v2z = (float) verts[2].vector3D.z * scale;

                // Matches TexturedQuad.draw: cross((v2-v1), (v0-v1)). Yields the same
                // outward normal vanilla uses (or inward for mirrored boxes — flipFace
                // reverses the vertex order, which inverts the cross-product sign).
                edge1.set(v2x - v1x, v2y - v1y, v2z - v1z);
                edge2.set(v0x - v1x, v0y - v1y, v0z - v1z);
                edge1.cross(edge2, normal);
                if (normal.lengthSquared() > 0F) normal.normalize();

                for (int i = 0; i < 4; i++) {
                    PositionTextureVertex pv = verts[i];
                    meshVertices.x(vertex, (float) pv.vector3D.x * scale);
                    meshVertices.y(vertex, (float) pv.vector3D.y * scale);
                    meshVertices.z(vertex, (float) pv.vector3D.z * scale);
                    meshVertices.r(vertex, 1F);
                    meshVertices.g(vertex, 1F);
                    meshVertices.b(vertex, 1F);
                    meshVertices.a(vertex, 1F);
                    meshVertices.u(vertex, pv.texturePositionX);
                    meshVertices.v(vertex, pv.texturePositionY);
                    meshVertices.overlay(vertex, OverlayTexture.NO_OVERLAY);
                    meshVertices.light(vertex, 0);
                    meshVertices.normalX(vertex, normal.x);
                    meshVertices.normalY(vertex, normal.y);
                    meshVertices.normalZ(vertex, normal.z);
                    vertex++;
                }
            }
        }

        return new SimpleQuadMesh(meshVertices, renderer.boxName != null ? renderer.boxName : "ModelRenderer");
    }
}
