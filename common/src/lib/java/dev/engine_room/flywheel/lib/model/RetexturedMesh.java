package dev.engine_room.flywheel.lib.model;

import dev.engine_room.flywheel.api.model.IndexSequence;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import dev.engine_room.flywheel.lib.util.AnimatedSprites;
import dev.engine_room.flywheel.lib.vertex.VertexTransformations;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Vector4fc;

public record RetexturedMesh(Mesh mesh, TextureAtlasSprite sprite) implements Mesh {
    // Port: registers for Sodium's visible-only animation.
    public RetexturedMesh {
        AnimatedSprites.add(sprite);
    }

    @Override
    public int vertexCount() {
        return mesh.vertexCount();
    }

    @Override
    public void write(MutableVertexList vertexList) {
        mesh.write(vertexList);
        VertexTransformations.retexture(vertexList, sprite);
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
