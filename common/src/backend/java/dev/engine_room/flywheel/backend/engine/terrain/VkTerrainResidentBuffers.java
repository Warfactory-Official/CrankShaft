package dev.engine_room.flywheel.backend.engine.terrain;

import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class VkTerrainResidentBuffers implements TerrainResidentBuffers {
    private static final int STORAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    private static final int TRANSFER_SRC = VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    private static final int TRANSFER_DST = VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private static final int PAGE_SHIFT = 10;
    private static final long PAGE_SIZE = 1L << PAGE_SHIFT;

    private final List<Buffer> all = new ArrayList<>();
    private int readParity;

    private static int pageWords(long bytes) {
        return (int) ((((bytes + PAGE_SIZE - 1) >>> PAGE_SHIFT) + 63) >>> 6);
    }

    private static int nextSet(long[] bits, int from, int limit) {
        int w = from >>> 6;
        if (w >= bits.length) {
            return limit;
        }
        long word = bits[w] & (-1L << from);
        while (word == 0L) {
            if (++w >= bits.length) {
                return limit;
            }
            word = bits[w];
        }
        return Math.min(w * 64 + Long.numberOfTrailingZeros(word), limit);
    }

    private static int nextClear(long[] bits, int from, int limit) {
        int w = from >>> 6;
        if (w >= bits.length) {
            return limit;
        }
        long word = ~bits[w] & (-1L << from);
        while (word == 0L) {
            if (++w >= bits.length) {
                return limit;
            }
            word = ~bits[w];
        }
        return Math.min(w * 64 + Long.numberOfTrailingZeros(word), limit);
    }

    public void setReadParity(int parity) {
        readParity = parity;
    }

    public boolean hasDirty(int parity) {
        for (Buffer b : all) {
            if (b.dirty[parity]) {
                return true;
            }
        }
        return false;
    }

    public void recordUploads(VkCommandBuffer cmd, int parity) {
        boolean any = false;
        for (Buffer b : all) {
            any |= b.recordCopy(cmd, parity);
        }
        if (any) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                                                                .sType$Default()
                                                                .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                                                                .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT);
                // dst = ALL_COMMANDS: the metadata is consumed by compute (cull/emit), vertex/fragment (MDI + fade),
                VK12.vkCmdPipelineBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
            }
        }
    }

    @Override
    public TerrainResidentBuffer createMirror(long stride) {
        Buffer b = new Buffer(stride);
        all.add(b);
        return b;
    }

    @Override
    public TerrainResidentBuffer createDynamic() {
        Buffer b = new Buffer(Integer.BYTES);
        all.add(b);
        return b;
    }

    // VK folds host writes into the device copies via recordUploads(cmd, parity), driven by the manager on a command
    @Override
    public void flushPendingWrites() {
    }

    @Override
    public void flushBeforeGrow() {
    }

    private final class Buffer implements TerrainResidentBuffer {
        private final VkBuffer[] staging = new VkBuffer[2];
        private final VkBuffer[] device = new VkBuffer[2];
        // Dirty pages per parity, changed since that parity was last synced. One [lo, hi) span per buffer re-uploaded
        // ~950 MB/s in flight (scattered section feeds span most of the table).
        private final long[][] dirtyPages = new long[2][];
        private final boolean[] dirty = new boolean[2];
        private long shadowPtr;
        private long shadowBytes;

        Buffer(long initialBytes) {
            long bytes = Math.max(initialBytes, Integer.BYTES);
            this.shadowBytes = bytes;
            this.shadowPtr = FlwMemoryTracker.malloc(bytes);
            MemoryUtil.memSet(shadowPtr, 0, bytes);
            dirtyPages[0] = new long[pageWords(bytes)];
            dirtyPages[1] = new long[pageWords(bytes)];
            try {
                staging[0] = new VkBuffer(TRANSFER_SRC, bytes);
                staging[1] = new VkBuffer(TRANSFER_SRC, bytes);
                device[0] = new VkBuffer(STORAGE | TRANSFER_DST, bytes, true);
                device[1] = new VkBuffer(STORAGE | TRANSFER_DST, bytes, true);
            } catch (Throwable t) {
                if (device[0] != null) {
                    device[0].delete();
                }
                if (staging[1] != null) {
                    staging[1].delete();
                }
                if (staging[0] != null) {
                    staging[0].delete();
                }
                MemoryUtil.nmemFree(shadowPtr);
                shadowPtr = 0L;
                throw t;
            }
        }

        @Override
        public boolean ensureCapacity(long bytes) {
            if (bytes <= shadowBytes) {
                return false;
            }
            long newBytes = Math.max(bytes, shadowBytes * 2);
            // Tracker realloc throws OutOfMemoryError on a NULL return; a raw nmemRealloc would hand the
            // following memSet a near-null pointer.
            shadowPtr = FlwMemoryTracker.realloc(shadowPtr, newBytes);
            MemoryUtil.memSet(shadowPtr + shadowBytes, 0, newBytes - shadowBytes);
            shadowBytes = newBytes;
            staging[0].ensureCapacity(newBytes);
            staging[1].ensureCapacity(newBytes);
            device[0].ensureCapacity(newBytes);
            device[1].ensureCapacity(newBytes);
            dirtyPages[0] = Arrays.copyOf(dirtyPages[0], pageWords(newBytes));
            dirtyPages[1] = Arrays.copyOf(dirtyPages[1], pageWords(newBytes));
            markDirty(0L, newBytes);
            return true;
        }

        @Override
        public long byteCapacity() {
            return shadowBytes;
        }

        @Override
        public void write(long offset, long srcPtr, long size) {
            if (offset + size > shadowBytes) {
                return;
            }
            MemoryUtil.memCopy(srcPtr, shadowPtr + offset, size);
            markDirty(offset, offset + size);
        }

        @Override
        public void clearRange(long offset, long size, int fillWord) {
            if (offset + size > shadowBytes) {
                return;
            }
            long ptr = shadowPtr + offset;
            if (fillWord == 0) {
                MemoryUtil.memSet(ptr, 0, size);
            } else {
                long words = size / Integer.BYTES;
                for (long i = 0; i < words; i++) {
                    MemoryUtil.memPutInt(ptr + i * Integer.BYTES, fillWord);
                }
            }
            markDirty(offset, offset + size);
        }

        private void markDirty(long lo, long hi) {
            if (hi <= lo) {
                return;
            }
            int first = (int) (lo >>> PAGE_SHIFT);
            int last = (int) ((hi - 1) >>> PAGE_SHIFT);
            for (int p = 0; p < 2; p++) {
                dirty[p] = true;
                long[] pages = dirtyPages[p];
                for (int w = first >>> 6; w <= last >>> 6; w++) {
                    long mask = -1L;
                    if (w == first >>> 6) {
                        mask &= -1L << first;
                    }
                    if (w == last >>> 6) {
                        mask &= -1L >>> (63 - (last & 63));
                    }
                    pages[w] |= mask;
                }
            }
        }

        boolean recordCopy(VkCommandBuffer cmd, int parity) {
            if (!dirty[parity]) {
                return false;
            }
            long[] pages = dirtyPages[parity];
            int pageCount = (int) ((shadowBytes + PAGE_SIZE - 1) >>> PAGE_SHIFT);
            int runs = 0;
            for (int page = nextSet(pages, 0, pageCount); page < pageCount; page = nextSet(pages, nextClear(pages, page, pageCount), pageCount)) {
                runs++;
            }
            VkBuffer stage = staging[parity];
            VkBufferCopy.Buffer regions = VkBufferCopy.malloc(runs);
            for (int page = nextSet(pages, 0, pageCount); page < pageCount; ) {
                int end = nextClear(pages, page, pageCount);
                long lo = (long) page << PAGE_SHIFT;
                long size = Math.min((long) end << PAGE_SHIFT, shadowBytes) - lo;
                MemoryUtil.memCopy(shadowPtr + lo, stage.mappedAddress() + lo, size);
                regions.get().srcOffset(lo).dstOffset(lo).size(size);
                page = nextSet(pages, end, pageCount);
            }
            regions.flip();
            VK12.vkCmdCopyBuffer(cmd, stage.vkBuffer(), device[parity].vkBuffer(), regions);
            regions.free();
            Arrays.fill(pages, 0L);
            dirty[parity] = false;
            return true;
        }

        @Override
        public long vkBuffer() {
            return device[readParity].vkBuffer();
        }

        @Override
        public void delete() {
            staging[0].delete();
            staging[1].delete();
            device[0].delete();
            device[1].delete();
            if (shadowPtr != 0L) {
                MemoryUtil.nmemFree(shadowPtr);
                shadowPtr = 0L;
            }
        }
    }
}
