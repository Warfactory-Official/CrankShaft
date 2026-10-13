package dev.engine_room.flywheel.lib.vertex;

import dev.engine_room.flywheel.api.vertex.MutableVertexList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
#if MC >= 26.3
import net.minecraft.client.renderer.texture.UvMapping;
#endif

public final class VertexTransformations {
    private VertexTransformations() {
    }

    public static void retexture(MutableVertexList vertexList, int index, TextureAtlasSprite sprite) {
        vertexList.u(index, sprite.getU(vertexList.u(index)));
        vertexList.v(index, sprite.getV(vertexList.v(index)));
    }

    public static void retexture(MutableVertexList vertexList, TextureAtlasSprite sprite) {
        for (int i = 0; i < vertexList.vertexCount(); i++) {
            retexture(vertexList, i, sprite);
        }
    }

    #if MC >= 26.3
    // The mapping must already be prepared; these methods only read UVs on the mesh-upload thread.
    public static void retexture(MutableVertexList vertexList, int index, UvMapping mapping) {
        vertexList.u(index, mapping.getU(vertexList.u(index)));
        vertexList.v(index, mapping.getV(vertexList.v(index)));
    }

    public static void retexture(MutableVertexList vertexList, UvMapping mapping) {
        for (int i = 0; i < vertexList.vertexCount(); i++) {
            retexture(vertexList, i, mapping);
        }
    }
    #endif
}
