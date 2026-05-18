package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.IndexSequence;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Vector4fc;

public record RetexturedMesh(Mesh mesh, TextureAtlasSprite sprite) implements Mesh {
    @Override
    public int vertexCount() {
        return mesh.vertexCount();
    }

    @Override
    public void write(MutableVertexList vertexList) {
        mesh.write(vertexList);

        // 1.12.2 TextureAtlasSprite has no getU(float)/getV(float); lerp manually.
        float minU = sprite.getMinU();
        float spanU = sprite.getMaxU() - minU;
        float minV = sprite.getMinV();
        float spanV = sprite.getMaxV() - minV;

        int count = vertexList.vertexCount();
        for (int i = 0; i < count; i++) {
            vertexList.u(i, minU + spanU * vertexList.u(i));
            vertexList.v(i, minV + spanV * vertexList.v(i));
        }
    }

    @Override
    public IndexSequence indexSequence() {
        return mesh.indexSequence();
    }

    @Override
    public int indexCount() {
        return mesh.indexCount();
    }

    @Override
    public Vector4fc boundingSphere() {
        return mesh.boundingSphere();
    }
}
