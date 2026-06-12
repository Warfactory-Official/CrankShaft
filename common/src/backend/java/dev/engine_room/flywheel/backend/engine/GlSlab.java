package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;

import java.util.ArrayList;
import java.util.List;

public final class GlSlab implements Slab {
    // No GL_CLIENT_STORAGE_BIT on purpose: the buffer stays in VRAM (write-combined BAR window) and the upload is
    // a cheap VRAM->VRAM glCopyNamedBufferSubData; host placement would turn that into a PCIe upload -- do not "fix" it.
    private static final int STORAGE_FLAGS = GL44C.GL_MAP_PERSISTENT_BIT | GL30C.GL_MAP_WRITE_BIT;
    private static final int MAP_FLAGS = GL44C.GL_MAP_PERSISTENT_BIT | GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_FLUSH_EXPLICIT_BIT;

    private final long pageSizeBytes;
    private int handle;
    private long ptr;
    private int pageCapacity;

    private List<Retired> retiredThisFrame = new ArrayList<>();
    private List<Retired> retiredLastFrame = new ArrayList<>();

    public GlSlab(long pageSizeBytes, int initialPages) {
        this.pageSizeBytes = pageSizeBytes;
        allocate(Math.max(initialPages, 4));
    }

    /**
     * The GL buffer name. The indirect upload reads this as the copy source.
     */
    public int handle() {
        return handle;
    }

    @Override
    public long ptrForPage(int pageNo) {
        return ptr + (long) pageNo * pageSizeBytes;
    }

    @Override
    public int pageCapacity() {
        return pageCapacity;
    }

    public long pageSizeBytes() {
        return pageSizeBytes;
    }

    public long capacityBytes() {
        return (long) pageCapacity * pageSizeBytes;
    }

    /**
     * Grow the buffer if {@code neededPages} exceeds capacity; the old mapped region is retained one extra frame.
     */
    @Override
    public boolean ensureCapacity(int neededPages) {
        if (neededPages <= pageCapacity) {
            return false;
        }
        int target = Math.max(neededPages, (int) Math.ceil(pageCapacity * 1.6));
        grow(target);
        return true;
    }

    @Override
    public void flushRange(long byteOffset, long byteSize) {
        if (byteSize > 0) {
            GL45C.glFlushMappedNamedBufferRange(handle, byteOffset, byteSize);
        }
    }

    /**
     * Free the buffer retired two frames ago; by then the GPU and the resize frame's worker writes are done.
     */
    @Override
    public void releaseRetired() {
        for (Retired r : retiredLastFrame) r.delete();
        retiredLastFrame.clear();
        List<Retired> tmp = retiredLastFrame;
        retiredLastFrame = retiredThisFrame;
        retiredThisFrame = tmp;
    }

    @Override
    public void delete() {
        if (handle != 0) {
            GL45C.glUnmapNamedBuffer(handle);
            GL15C.glDeleteBuffers(handle);
            FlwMemoryTracker._freeGpuMemory(capacityBytes());
            handle = 0;
            ptr = 0L;
            pageCapacity = 0;
        }
        for (Retired r : retiredThisFrame) r.delete();
        for (Retired r : retiredLastFrame) r.delete();
    }

    private void allocate(int pages) {
        int newHandle = GL45C.glCreateBuffers();
        long bytes = (long) pages * pageSizeBytes;
        GL45C.glNamedBufferStorage(newHandle, bytes, STORAGE_FLAGS);
        long newPtr = GL45C.nglMapNamedBufferRange(newHandle, 0, bytes, MAP_FLAGS);
        this.handle = newHandle;
        this.ptr = newPtr;
        this.pageCapacity = pages;
        FlwMemoryTracker._allocGpuMemory(bytes);
    }

    private void grow(int newPageCount) {
        retiredThisFrame.add(new Retired(handle, capacityBytes()));

        int oldHandle = handle;
        long oldBytes = capacityBytes();

        int newHandle = GL45C.glCreateBuffers();
        long newBytes = (long) newPageCount * pageSizeBytes;
        GL45C.glNamedBufferStorage(newHandle, newBytes, STORAGE_FLAGS);

        if (oldBytes > 0) {
            GL45C.glCopyNamedBufferSubData(oldHandle, newHandle, 0, 0, oldBytes);
        }

        long newPtr = GL45C.nglMapNamedBufferRange(newHandle, 0, newBytes, MAP_FLAGS);

        handle = newHandle;
        ptr = newPtr;
        pageCapacity = newPageCount;
        FlwMemoryTracker._allocGpuMemory(newBytes);
    }

    private record Retired(int handle, long bytes) {
        void delete() {
            if (handle != 0) {
                GL45C.glUnmapNamedBuffer(handle);
                GL15C.glDeleteBuffers(handle);
                FlwMemoryTracker._freeGpuMemory(bytes);
            }
        }
    }
}
