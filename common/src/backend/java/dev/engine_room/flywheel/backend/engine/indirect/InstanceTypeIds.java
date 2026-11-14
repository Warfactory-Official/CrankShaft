package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceType;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.List;

public final class InstanceTypeIds {
    public static final int MAX_TYPES = 1 << 16;

    private static final Object2IntOpenHashMap<InstanceType<?>> IDS = new Object2IntOpenHashMap<>();
    private static final List<InstanceType<?>> TYPES = new ArrayList<>();
    private static volatile Snapshot snapshot = new Snapshot(List.of());
    private static volatile int generation;

    static {
        IDS.defaultReturnValue(-1);
    }

    private InstanceTypeIds() {
    }

    public static synchronized int id(InstanceType<? extends Instance> type) {
        int id = IDS.getInt(type);
        if (id >= 0) {
            return id;
        }
        id = TYPES.size();
        if (id >= MAX_TYPES) {
            throw new IllegalStateException("Too many instance types registered: " + id);
        }
        IDS.put(type, id);
        TYPES.add(type);
        snapshot = new Snapshot(List.copyOf(TYPES));
        generation = TYPES.size();
        return id;
    }

    /**
     * Render-path cache key; type registration publishes a new generation.
     */
    public static int generation() {
        return generation;
    }

    public static synchronized int count() {
        return TYPES.size();
    }

    public static Snapshot snapshot() {
        return snapshot;
    }

    public record Snapshot(List<InstanceType<?>> types) {
    }
}
