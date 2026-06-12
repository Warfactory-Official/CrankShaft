package dev.engine_room.flywheel.backend.engine;

/**
 * A page-partitioned, persistently-mapped GPU buffer that backs an instancer's instance data. The GL and Vulkan
 * indirect backends supply their own implementation ({@link GlSlab} / a Vulkan slab): the instancer writes instances
 * through the host-visible mapped pointers from {@link #ptrForPage}, and the backend uploads dirty pages from the slab
 * into its object buffer. Worker pointer arithmetic is identical across backends, so {@link IndirectInstancer} is
 * shared; only the allocation/flush/upload leaves differ.
 */
public interface Slab {
    /**
     * Host-visible mapped write pointer for the start of the given page.
     */
    long ptrForPage(int pageNo);

    int pageCapacity();

    /**
     * Grow to hold at least {@code neededPages}; returns {@code true} if a grow (and thus a remap) occurred.
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
