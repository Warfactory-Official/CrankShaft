package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL44C;
import org.lwjgl.opengl.GL45C;

import java.util.ArrayList;
import java.util.List;

public final class SlabBuffer {
    // No GL_CLIENT_STORAGE_BIT on purpose. Drivers place this in VRAM and expose the mapped
    // pointer as a write-combined PCIe (BAR) window. Per-byte WC writes are slower than cached
    // host writes in isolation, but instance writers run in parallel across worker threads
    // touching independent pages, so aggregate issue rate hides the cost. In exchange the
    // indirect path's upload step is a cheap VRAM->VRAM glCopyNamedBufferSubData per dirty page
    // (no compute scatter, no host staging round-trip). Adding GL_CLIENT_STORAGE_BIT would force
    // host placement, turning that copy into a PCIe upload and shifting RAM pressure off VRAM --
    // do not "fix" it.
    private static final int STORAGE_FLAGS = GL44C.GL_MAP_PERSISTENT_BIT | GL30C.GL_MAP_WRITE_BIT;
    private static final int MAP_FLAGS = GL44C.GL_MAP_PERSISTENT_BIT | GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_FLUSH_EXPLICIT_BIT;

    private final long pageSizeBytes;
    private int handle;
    private long ptr;
    private int pageCapacity;

    private List<Retired> retiredThisFrame = new ArrayList<>();
    private List<Retired> retiredLastFrame = new ArrayList<>();

    public SlabBuffer(long pageSizeBytes, int initialPages) {
        this.pageSizeBytes = pageSizeBytes;
        allocate(Math.max(initialPages, 4));
    }

    public int handle() {
        return handle;
    }

    public long ptrForPage(int pageNo) {
        return ptr + (long) pageNo * pageSizeBytes;
    }

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
     * Grow the buffer if {@code neededPages} exceeds current capacity.
     * Old mapped region is retained for one extra frame.
     * Returns {@code true} if a grow occurred.
     */
    public boolean ensureCapacity(int neededPages) {
        if (neededPages <= pageCapacity) {
            return false;
        }
        int target = Math.max(neededPages, (int) Math.ceil(pageCapacity * 1.6));
        grow(target);
        return true;
    }

    public void flushRange(long byteOffset, long byteSize) {
        if (byteSize > 0) {
            GL45C.glFlushMappedNamedBufferRange(handle, byteOffset, byteSize);
        }
    }

    /**
     * Free the buffer that was retired two frames ago. Call at the start of
     * each frame; this is the safe point because (a) the GPU has long since
     * finished reading from it, and (b) all worker writes from the resize
     * frame have completed.
     */
    public void releaseRetired() {
        for (Retired r : retiredLastFrame) r.delete();
        retiredLastFrame.clear();
        List<Retired> tmp = retiredLastFrame;
        retiredLastFrame = retiredThisFrame;
        retiredThisFrame = tmp;
    }

    public void delete() {
        if (handle != 0) {
            GL45C.glUnmapNamedBuffer(handle);
            GL15C.glDeleteBuffers(handle);
            FlwMemoryTracker._freeGpuMemory(capacityBytes());
            handle = 0;
            ptr = 0L;
            pageCapacity = 0;
        }
        // no need to clear() them since this instance is about to be discarded anyway
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
