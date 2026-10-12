package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.engine.Slab;
import dev.engine_room.flywheel.backend.engine.SlabPageCopier;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GL indirect {@link Slab}s carved into stable host-memory page slots. Dirty live ranges snapshot into a batched
 * upload before the GPU reads them, so later instance writes cannot change an in-flight upload.
 * A freed slot is reused two frames later. Allocation and upload run on the render thread.
 */
final class GlSlabArena implements SlabPageCopier {
    private static final long FIRST_CHUNK_BYTES = 1L << 20;
    private static final long MAX_CHUNK_BYTES = 16L << 20;

    private final StagingBuffer stagingBuffer;
    private final List<Chunk> chunks = new ArrayList<>();
    private final Long2ObjectOpenHashMap<LongArrayList> freeSlots = new Long2ObjectOpenHashMap<>();
    private int dstVbo;
    private List<ArenaSlab> retiredThisFrame = new ArrayList<>();
    private List<ArenaSlab> retiredLastFrame = new ArrayList<>();

    GlSlabArena(StagingBuffer stagingBuffer) {
        this.stagingBuffer = stagingBuffer;
    }

    private static int chunkIndex(long slot) {
        return (int) (slot >>> 32);
    }

    private static long offset(long slot) {
        return slot & 0xFFFFFFFFL;
    }

    Slab create(long pageBytes, int initialPages) {
        return new ArenaSlab(pageBytes);
    }

    /**
     * Once per frame, before any slab allocation.
     */
    void beginFrame() {
        for (ArenaSlab slab : retiredLastFrame) {
            LongArrayList free = freeSlots.computeIfAbsent(slab.pageBytes, b -> new LongArrayList());
            free.addElements(free.size(), slab.slots, 0, slab.slotCount);
        }
        retiredLastFrame.clear();
        List<ArenaSlab> tmp = retiredLastFrame;
        retiredLastFrame = retiredThisFrame;
        retiredThisFrame = tmp;
    }

    void beginUpload(int dstVbo) {
        this.dstVbo = dstVbo;
    }

    @Override
    public void copyPage(Slab source, long srcByteOffset, long dstByteOffset, long byteSize) {
        ArenaSlab slab = (ArenaSlab) source;
        long pageBytes = slab.pageBytes;
        int page = (int) (srcByteOffset / pageBytes);
        long slot = slab.slots[page];
        Chunk chunk = chunks.get(chunkIndex(slot));
        long src = offset(slot) + srcByteOffset - page * pageBytes;
        stagingBuffer.enqueueBatchedCopy(chunk.ptr + src, byteSize, dstVbo, dstByteOffset);
    }

    void delete() {
        for (Chunk chunk : chunks) {
            chunk.delete();
        }
        chunks.clear();
        freeSlots.clear();
        retiredThisFrame.clear();
        retiredLastFrame.clear();
    }

    private long allocate(long pageBytes) {
        LongArrayList free = freeSlots.get(pageBytes);
        if (free != null && !free.isEmpty()) {
            return free.popLong();
        }
        Chunk chunk = chunks.isEmpty() ? null : chunks.getLast();
        if (chunk == null || chunk.top + pageBytes > chunk.capacity) {
            long capacity = chunk == null ? FIRST_CHUNK_BYTES : Math.min(chunk.capacity * 2, MAX_CHUNK_BYTES);
            chunk = new Chunk(Math.max(capacity, pageBytes));
            chunks.add(chunk);
        }
        long slot = ((long) (chunks.size() - 1) << 32) | chunk.top;
        chunk.top += pageBytes;
        return slot;
    }

    private static final class Chunk {
        private final long ptr;
        private final long capacity;
        private long top;

        private Chunk(long capacity) {
            this.capacity = capacity;
            ptr = MemoryUtil.nmemAlloc(capacity);
            if (ptr == MemoryUtil.NULL) throw new OutOfMemoryError("Allocating instance arena chunk of " + capacity + " bytes");
            FlwMemoryTracker._allocCpuMemory(capacity);
        }

        private void delete() {
            MemoryUtil.nmemFree(ptr);
            FlwMemoryTracker._freeCpuMemory(capacity);
        }
    }

    private final class ArenaSlab implements Slab {
        private final long pageBytes;
        private long[] slots = new long[0];
        private int slotCount;

        private ArenaSlab(long pageBytes) {
            this.pageBytes = pageBytes;
        }

        @Override
        public long ptrForPage(int pageNo) {
            long slot = slots[pageNo];
            return chunks.get(chunkIndex(slot)).ptr + offset(slot);
        }

        @Override
        public int pageCapacity() {
            return slotCount;
        }

        // Slots never move: nothing to remap.
        @Override
        public boolean ensureCapacity(int neededPages) {
            if (neededPages > slots.length) {
                slots = Arrays.copyOf(slots, Math.max(neededPages, slots.length * 2));
            }
            while (slotCount < neededPages) {
                slots[slotCount++] = allocate(pageBytes);
            }
            return false;
        }

        @Override
        public void flushRange(long byteOffset, long byteSize) {
        }

        @Override
        public void releaseRetired() {
        }

        @Override
        public void delete() {
            retiredThisFrame.add(this);
        }
    }
}
