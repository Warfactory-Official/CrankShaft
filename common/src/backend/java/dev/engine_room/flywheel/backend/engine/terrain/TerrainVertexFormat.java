package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;

/**
 * The chunk vertex layout Sodium's arenas actually hold, which is NOT constant: Iris swaps the chunk vertex type
 * while a shaderpack is loaded (its layout is the compact one plus four shader-input fields, stride 20 -> 36), so
 * nothing may bake in {@code CompactChunkVertex}. Vertex addressing is pointer arithmetic on the struct size, so a
 * stale stride reads the WRONG VERTEX rather than mis-decoding one.
 * <p>
 * Format-keyed pipeline/program caches hold the {@link VertexFormat} they were built against and drop themselves
 * when {@link #current()} no longer matches.
 */
public final class TerrainVertexFormat {
    private static final int COMPACT_UINTS = 5;

    private TerrainVertexFormat() {
    }

    public static VertexFormat current() {
        return ChunkMeshFormats.getCurrent()
                               .getVertexFormat();
    }

    public static int strideBytes() {
        return current().getVertexSize();
    }

    public static int uintCount() {
        return strideBytes() / Integer.BYTES;
    }

    public static boolean extended() {
        return uintCount() > COMPACT_UINTS;
    }

    /**
     * Sizes the GLSL {@code Vertex} struct; the decode helpers read only the leading compact fields.
     */
    public static void appendDefines(Compilation ctx) {
        if (extended()) {
            ctx.define("_FLW_TERRAIN_VERTEX_EXTENDED");
        }
    }
}
