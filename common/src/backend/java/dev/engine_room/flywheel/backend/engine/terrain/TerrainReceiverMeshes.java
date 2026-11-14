package dev.engine_room.flywheel.backend.engine.terrain;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Borrowed CPU mesh ranges. An upload captures the IDs before MeshData releases its vertex memory.
 */
public final class TerrainReceiverMeshes {
    private static final Map<Long, int[]> PENDING = new ConcurrentHashMap<>();

    private TerrainReceiverMeshes() {
    }

    public static void publish(long address, int[] ids) {
        var existing = PENDING.putIfAbsent(address, ids);
        if (existing != null && existing != ids)
            throw new IllegalStateException("Overlapping terrain receiver mesh ranges");
    }

    public static int[] find(ByteBuffer vertices) {
        return PENDING.getOrDefault(MemoryUtil.memAddress(vertices), TerrainReceiverCompilation.EMPTY);
    }

    public static int[] findBase(ByteBuffer vertices) {
        return PENDING.getOrDefault(MemoryUtil.memAddress0(vertices), TerrainReceiverCompilation.EMPTY);
    }

    public static void remove(long address, int[] ids) {
        PENDING.remove(address, ids);
    }
}
