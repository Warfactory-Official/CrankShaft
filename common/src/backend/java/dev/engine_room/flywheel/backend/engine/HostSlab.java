package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Contiguous host-memory instance pages. Instancing uploads texels from them; Vulkan indirect snapshots live
 * ranges into fenced frame storage before GPU consumption.
 * Allocation, growth and retirement run on the render thread after instance updates join.
 */
public final class HostSlab implements Slab {
    private final long pageSizeBytes;
    private long ptr;
    private int pageCapacity;

    private List<Retired> retiredThisFrame = new ArrayList<>();
    private List<Retired> retiredLastFrame = new ArrayList<>();

    public HostSlab(long pageSizeBytes, int initialPages) {
        this.pageSizeBytes = pageSizeBytes;
        pageCapacity = Math.max(initialPages, 4);
        ptr = allocate(capacityBytes());
    }

    private static long allocate(long bytes) {
        FlwMemoryTracker._allocCpuMemory(bytes);
        long ptr = MemoryUtil.nmemAlloc(bytes);
        if (ptr == MemoryUtil.NULL) throw new OutOfMemoryError("Allocating instance slab of " + bytes + " bytes");
        return ptr;
    }

    public long ptrForPage(int pageNo) {
        return ptr + (long) pageNo * pageSizeBytes;
    }

    public int pageCapacity() {
        return pageCapacity;
    }

    @Override
    public void flushRange(long byteOffset, long byteSize) {
    }

    private long capacityBytes() {
        return (long) pageCapacity * pageSizeBytes;
    }

    /**
     * Grow to hold at least {@code neededPages}; {@code true} if the pages moved. The old block is freed two frames
     * later.
     */
    public boolean ensureCapacity(int neededPages) {
        if (neededPages <= pageCapacity) {
            return false;
        }
        long oldBytes = capacityBytes();
        pageCapacity = Math.max(neededPages, (int) Math.ceil(pageCapacity * 1.6));
        long newPtr = allocate(capacityBytes());
        MemoryUtil.memCopy(ptr, newPtr, oldBytes);
        retiredThisFrame.add(new Retired(ptr, oldBytes));
        ptr = newPtr;
        return true;
    }

    /**
     * Frees blocks retired two frames ago. Once per frame.
     */
    public void releaseRetired() {
        for (Retired r : retiredLastFrame) r.free();
        retiredLastFrame.clear();
        List<Retired> tmp = retiredLastFrame;
        retiredLastFrame = retiredThisFrame;
        retiredThisFrame = tmp;
    }

    public void delete() {
        if (ptr != 0L) {
            new Retired(ptr, capacityBytes()).free();
            ptr = 0L;
            pageCapacity = 0;
        }
        for (Retired r : retiredThisFrame) r.free();
        for (Retired r : retiredLastFrame) r.free();
        retiredThisFrame.clear();
        retiredLastFrame.clear();
    }

    private record Retired(long ptr, long bytes) {
        void free() {
            MemoryUtil.nmemFree(ptr);
            FlwMemoryTracker._freeCpuMemory(bytes);
        }
    }
}
