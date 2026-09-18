package dev.engine_room.flywheel.backend.engine.terrain;

import org.lwjgl.system.MemoryUtil;

public final class TerrainRegionInput {
    public static final int STRIDE = 16;

    private TerrainRegionInput() {
    }

    /** Packs a render-thread region record consumed by native and guest terrain shaders. X/Z use signed 24-bit
     * chunk coordinates (beyond Minecraft's world border), Y uses signed 16-bit; the record remains 16 bytes. */
    public static void write(long address, int x, int y, int z, int regionId, int run) {
        MemoryUtil.memPutInt(address, (x & 0xFFFF) | (z << 16));
        MemoryUtil.memPutInt(address + 4, (y & 0xFFFF) | (x & 0xFF0000) | ((z & 0xFF0000) << 8));
        MemoryUtil.memPutInt(address + 8, regionId);
        MemoryUtil.memPutInt(address + 12, run);
    }
}
