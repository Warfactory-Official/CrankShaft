package dev.engine_room.flywheel.backend.engine.indirect;

import org.lwjgl.opengl.*;

import java.util.TreeMap;

public final class SharedStorageArena {
    public final ResizableStorageBuffer buffer = new ResizableStorageBuffer();
    private final TreeMap<Long, Long> free = new TreeMap<>();
    private final long alignment;
    private final long limit = GL32.glGetInteger64(GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);

    public SharedStorageArena(long stride) {
        var ssboAlignment = (long) GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        var a = ssboAlignment;
        var b = stride;
        while (b != 0) {
            var remainder = a % b;
            a = b;
            b = remainder;
        }
        alignment = ssboAlignment / a * stride;
    }

    public Region region() {
        return new Region();
    }

    private long aligned(long bytes) {
        return (bytes + alignment - 1) / alignment * alignment;
    }

    private long allocate(long bytes) {
        for (var iterator = free.entrySet().iterator(); iterator.hasNext(); ) {
            var block = iterator.next();
            if (block.getValue() < bytes) continue;
            var offset = block.getKey();
            var remaining = block.getValue() - bytes;
            iterator.remove();
            if (remaining > 0) free.put(offset + bytes, remaining);
            return offset;
        }
        var previous = buffer.capacity();
        var next = Math.min(limit, aligned(Math.max(previous * 2, previous + Math.max(bytes, 65536))));
        if (next - previous < bytes) throw new IllegalStateException(
                "Shared instance storage exceeds the device SSBO limit of " + limit + " bytes");
        // Cull/apply shaders can have written the buffer before a later frame grows it.
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        buffer.ensureCapacity(next);
        if (next - previous > bytes) release(previous + bytes, next - previous - bytes);
        return previous;
    }

    private void release(long offset, long bytes) {
        var left = free.lowerEntry(offset);
        if (left != null && left.getKey() + left.getValue() == offset) {
            offset = left.getKey();
            bytes += left.getValue();
            free.remove(left.getKey());
        }
        var right = free.ceilingEntry(offset);
        if (right != null && offset + bytes == right.getKey()) {
            bytes += right.getValue();
            free.remove(right.getKey());
        }
        free.put(offset, bytes);
    }

    public void delete() {
        buffer.delete();
        free.clear();
    }

    public final class Region {
        private long offset;
        private long capacity;
        private long allocated;

        public long offset() {
            return offset;
        }

        public long capacity() {
            return capacity;
        }

        public void ensureCapacity(long bytes) {
            if (bytes <= capacity) return;
            var size = aligned(bytes);
            if (size > allocated) {
                var next = allocate(size);
                if (capacity > 0) {
                    GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
                    GL45.glCopyNamedBufferSubData(buffer.handle(), buffer.handle(), offset, next, capacity);
                    release(offset, allocated);
                }
                offset = next;
                allocated = size;
            }
            capacity = bytes;
        }

        public void delete() {
            if (allocated > 0) release(offset, allocated);
            capacity = allocated = 0;
        }
    }
}
