package dev.engine_room.flywheel.backend.engine.shaderpack;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.math.BlockPos;

public interface TerrainBuffer {
    static Slice source(VertexBuffer buffer) {
        return buffer instanceof OptifineTerrainBuffer optifine ? optifine.flywheel$sourceSlice()
                : ((TerrainBuffer) buffer).flywheel$sourceSlice(buffer.glBufferId, 0, false);
    }

    static BlockPos origin(RenderChunk chunk, Slice slice) {
        var pos = chunk.getPosition();
        return slice.region() ? new BlockPos(pos.getX() >> 8 << 8, pos.getY() >> 8 << 8, pos.getZ() >> 8 << 8) : pos;
    }

    static int originCoordinate(int coordinate, boolean region) { return region ? coordinate >> 8 << 8 : coordinate; }

    Slice flywheel$sourceSlice(int buffer, long offset, boolean region);

    long flywheel$uploadVersion();

    // Render Regions uses a 256-block origin and bypasses RenderChunk's per-chunk expansion.
    record Slice(int buffer, long offset, boolean region) {
        public Slice(int buffer, long offset) {
            this(buffer, offset, false);
        }
    }
}
