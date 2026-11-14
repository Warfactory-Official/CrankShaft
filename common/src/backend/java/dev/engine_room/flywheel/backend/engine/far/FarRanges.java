package dev.engine_room.flywheel.backend.engine.far;

import java.util.TreeMap;

/**
 * Reuses contiguous arena ranges, coalescing adjacent releases. Owned by the render thread.
 */
public final class FarRanges {
    private final TreeMap<Integer, Integer> free = new TreeMap<>();
    private int end;

    public int allocate(int count) {
        for (var range : free.entrySet()) {
            if (range.getValue() < count) continue;
            int start = range.getKey(), remaining = range.getValue() - count;
            free.remove(start);
            if (remaining != 0) free.put(start + count, remaining);
            return start;
        }
        int start = end;
        end += count;
        return start;
    }

    public void free(int start, int count) {
        var before = free.lowerEntry(start);
        if (before != null && before.getKey() + before.getValue() == start) {
            start = before.getKey();
            count += before.getValue();
            free.remove(before.getKey());
        }
        var after = free.ceilingEntry(start);
        if (after != null && start + count == after.getKey()) {
            count += after.getValue();
            free.remove(after.getKey());
        }
        free.put(start, count);
    }

    public int size() {
        return end;
    }

    public void clear() {
        free.clear();
        end = 0;
    }
}
