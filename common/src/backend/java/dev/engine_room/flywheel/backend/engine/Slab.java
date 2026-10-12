package dev.engine_room.flywheel.backend.engine;

/**
 * Native instance pages backing one indirect instancer: host-memory arena slots on GL, contiguous {@link HostSlab}
 * storage on Vulkan. The instancer writes through the per-page pointers from {@link #ptrForPage} (pages need not
 * be contiguous), and the backend uploads dirty pages into its object buffer.
 */
public interface Slab {
    /**
     * Native write pointer for the start of the given page.
     */
    long ptrForPage(int pageNo);

    int pageCapacity();

    /**
     * Grow to hold at least {@code neededPages}; {@code true} if existing pages moved.
     */
    boolean ensureCapacity(int neededPages);

    /**
     * Publish writes to a mapped region before upload; no-op for ordinary host memory or coherent mappings.
     */
    void flushRange(long byteOffset, long byteSize);

    /**
     * Free the allocation retired two frames ago. Call once at the start of each frame.
     */
    void releaseRetired();

    void delete();
}
