package dev.engine_room.flywheel.backend.engine;

/**
 * Persistently mapped instance pages backing one indirect instancer: {@code GlSlabArena} slots on GL, {@link VkSlab}
 * on Vulkan. The instancer writes through the per-page pointers from {@link #ptrForPage} (pages need not be
 * contiguous), and the backend uploads dirty pages into its object buffer.
 */
public interface Slab {
    /**
     * Host-visible mapped write pointer for the start of the given page.
     */
    long ptrForPage(int pageNo);

    int pageCapacity();

    /**
     * Grow to hold at least {@code neededPages}; {@code true} if existing pages moved.
     */
    boolean ensureCapacity(int neededPages);

    /**
     * Flush a written byte range of the mapped region so the GPU sees it (no-op on coherent memory).
     */
    void flushRange(long byteOffset, long byteSize);

    /**
     * Free the allocation retired two frames ago. Call once at the start of each frame.
     */
    void releaseRetired();

    void delete();
}
