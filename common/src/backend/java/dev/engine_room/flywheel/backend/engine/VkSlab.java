package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;

/**
 * Vulkan {@link Slab}: a host-visible, persistently-mapped {@link VkBuffer} carved into fixed-size instance pages.
 * The instancer writes instances through {@link #ptrForPage}; the indirect upload copies dirty pages into the object
 * buffer via {@code vkCmdCopyBuffer} (the slab is a transfer source, never bound as a storage buffer). Worker pointer
 * arithmetic is identical to {@link dev.engine_room.flywheel.backend.engine.GlSlab}, so {@code IndirectInstancer} is
 * shared.
 */
public final class VkSlab implements Slab {
    // Copy source for the per-page slab->object copy; the slab itself is never read by shaders.
    private static final int USAGE = VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

    private final long pageSizeBytes;
    private VkBuffer buffer;
    private int pageCapacity;

    public VkSlab(long pageSizeBytes, int initialPages) {
        this.pageSizeBytes = pageSizeBytes;
        int pages = Math.max(initialPages, 4);
        this.buffer = new VkBuffer(USAGE, (long) pages * pageSizeBytes);
        this.pageCapacity = pages;
    }

    /**
     * The raw {@code VkBuffer} handle, read by the page copier as the {@code vkCmdCopyBuffer} source.
     */
    public long vkBuffer() {
        return buffer.vkBuffer();
    }

    /**
     * The persistent host-mapped base pointer, for WORKER instance writes only. The mapping is a
     * write-combined BAR window: never host-READ it on a hot path (uncached PCIe reads) -- the
     * slab-&gt;object upload is a recorded {@code vkCmdCopyBuffer}.
     */
    public long mappedAddress() {
        return buffer.mappedAddress();
    }

    @Override
    public long ptrForPage(int pageNo) {
        return buffer.mappedAddress() + (long) pageNo * pageSizeBytes;
    }

    @Override
    public int pageCapacity() {
        return pageCapacity;
    }

    @Override
    public boolean ensureCapacity(int neededPages) {
        if (neededPages <= pageCapacity) {
            return false;
        }
        int target = Math.max(neededPages, (int) Math.ceil(pageCapacity * 1.6));
        long oldBytes = (long) pageCapacity * pageSizeBytes;

        VkBuffer next = new VkBuffer(USAGE, (long) target * pageSizeBytes);
        // Host copy on purpose despite the WC-read cost (grow-only, amortized away in steady state): the caller
        // (prepareUpload) host-writes transient pages into the new mapping right after, so the migration must
        // complete on the host timeline -- a recorded GPU copy would race those writes.
        MemoryUtil.memCopy(buffer.mappedAddress(), next.mappedAddress(), oldBytes);
        // deferDestroy retires the old buffer after the submit that last copied from it, so no extra retirement ring.
        buffer.delete();

        buffer = next;
        pageCapacity = target;
        return true;
    }

    @Override
    public void flushRange(long byteOffset, long byteSize) {
        // Host-coherent: no explicit flush needed.
    }

    @Override
    public void releaseRetired() {
        // Retirement is handled by VkContext.deferDestroy in ensureCapacity/delete; nothing to do per frame.
    }

    @Override
    public void delete() {
        buffer.delete();
    }
}
