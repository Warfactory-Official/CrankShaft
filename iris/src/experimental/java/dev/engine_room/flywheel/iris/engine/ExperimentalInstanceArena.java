package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.irisshaders.iris.Iris;

import java.util.TreeMap;

/**
 * Render-thread GPU storage with coalesced free ranges and growth-preserved contents.
 */
final class ExperimentalInstanceArena implements AutoCloseable {
    private final TreeMap<Long, Long> free = new TreeMap<>();
    private GpuBuffer buffer;
    private long end;

    long allocate(long bytes) {
        for (var entry : free.entrySet()) {
            if (entry.getValue() >= bytes) {
                long offset = entry.getKey();
                long remaining = entry.getValue() - bytes;
                free.remove(offset);
                if (remaining != 0) free.put(offset + bytes, remaining);
                return offset;
            }
        }
        long offset = end;
        end = Math.addExact(end, bytes);
        if (buffer == null || buffer.size() < end) {
            GpuBuffer next = Iris.getRenderBackend().createStorageBuffer("flywheel guest instance arena",
                    Math.max(end, buffer == null ? 65536 : Math.multiplyExact(buffer.size(), 2)), null);
            if (buffer != null) {
                RenderSystem.getDevice().createCommandEncoder()
                            .copyToBuffer(buffer.slice(), next.slice(0, buffer.size()));
                Iris.getRenderBackend().retire(buffer::close);
            }
            buffer = next;
        }
        return offset;
    }

    void free(long offset, long bytes) {
        var before = free.lowerEntry(offset);
        if (before != null && before.getKey() + before.getValue() == offset) {
            offset = before.getKey();
            bytes += before.getValue();
            free.remove(before.getKey());
        }
        var after = free.ceilingEntry(offset);
        if (after != null && offset + bytes == after.getKey()) {
            bytes += after.getValue();
            free.remove(after.getKey());
        }
        if (offset + bytes == end) end = offset;
        else free.put(offset, bytes);
    }

    GpuBuffer buffer() {
        return buffer;
    }

    @Override
    public void close() {
        if (buffer != null) Iris.getRenderBackend().retire(buffer::close);
        free.clear();
    }
}
