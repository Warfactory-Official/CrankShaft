package dev.engine_room.flywheel.backend.engine.far;

import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

/**
 * A GPU buffer written in place, grown by doubling with its contents kept. Render thread.
 */
public final class FarBuffer {
    private int handle;
    private long capacity;

    public int handle() {
        return handle;
    }

    public long capacity() {
        return capacity;
    }

    public void ensure(long bytes) {
        ensure(bytes, Long.MAX_VALUE);
    }

    /**
     * Bounded shader-storage arena growth; capacity pressure is reported before an invalid allocation.
     */
    public void ensure(long bytes, long limit) {
        if (bytes > limit) throw new IllegalStateException("Far buffer exceeds its GPU storage capacity: " + bytes);
        if (bytes <= capacity) {
            return;
        }
        long grown = Math.min(Math.max(Math.max(bytes, capacity * 2), 256), limit);
        int next = GL45C.glCreateBuffers();
        GL45C.glNamedBufferStorage(next, grown, GL44C.GL_DYNAMIC_STORAGE_BIT);
        if (handle != 0) {
            GL45C.glCopyNamedBufferSubData(handle, next, 0, 0, capacity);
            GL15C.glDeleteBuffers(handle);
            FlwMemoryTracker._freeGpuMemory(capacity);
        }
        handle = next;
        capacity = grown;
        FlwMemoryTracker._allocGpuMemory(capacity);
    }

    public void write(long offset, long ptr, long size) {
        GL45C.nglNamedBufferSubData(handle, offset, size, ptr);
    }

    public void write(long offset, int[] data, int count) {
        IntBuffer buffer = MemoryUtil.memAllocInt(count);
        try {
            buffer.put(data, 0, count).flip();
            GL45C.nglNamedBufferSubData(handle, offset, (long) count * 4, MemoryUtil.memAddress(buffer));
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    public void bindStorage(int binding) {
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, handle);
    }

    public void bindUniform(int binding) {
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, binding, handle);
    }

    public void delete() {
        if (handle != 0) {
            GL15C.glDeleteBuffers(handle);
            FlwMemoryTracker._freeGpuMemory(capacity);
            handle = 0;
            capacity = 0;
        }
    }
}
