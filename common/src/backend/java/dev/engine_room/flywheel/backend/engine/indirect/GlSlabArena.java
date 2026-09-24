package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.engine.Slab;
import dev.engine_room.flywheel.backend.engine.SlabPageCopier;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GL indirect {@link Slab}s carved into page slots of shared, persistently mapped chunks, so a frame's dirty pages reach
 * the object buffer through one scatter dispatch per chunk rather than one {@code glCopyNamedBufferSubData} per page.
 * Slots never move; a freed slot is reused two frames later. Render thread only.
 */
final class GlSlabArena implements SlabPageCopier {
    private static final long FIRST_CHUNK_BYTES = 1L << 20;
    // Scatter source offsets are 24-bit uint indices (64 MiB).
    private static final long MAX_CHUNK_BYTES = 16L << 20;
    private static final long COPY_BYTES = 64 * Integer.BYTES;
    // Scatter loads a full workgroup row before its size check.
    private static final long TAIL_BYTES = COPY_BYTES;
    private static final long COPY_STRIDE = 2 * Integer.BYTES;
    private static final int STORAGE_FLAGS = GL44C.GL_MAP_PERSISTENT_BIT | GL30C.GL_MAP_WRITE_BIT;
    private static final int MAP_FLAGS = GL44C.GL_MAP_PERSISTENT_BIT | GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_FLUSH_EXPLICIT_BIT;

    private final IndirectPrograms programs;
    private final List<Chunk> chunks = new ArrayList<>();
    private final Long2ObjectOpenHashMap<LongArrayList> freeSlots = new Long2ObjectOpenHashMap<>();
    private final GlBuffer copyBuffer = new GlBuffer(GlBufferUsage.STREAM_COPY);
    private List<ArenaSlab> retiredThisFrame = new ArrayList<>();
    private List<ArenaSlab> retiredLastFrame = new ArrayList<>();

    GlSlabArena(IndirectPrograms programs) {
        this.programs = programs;
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

    @Override
    public void copyPage(Slab source, long srcByteOffset, long dstByteOffset, long byteSize) {
        ArenaSlab slab = (ArenaSlab) source;
        long pageBytes = slab.pageBytes;
        int page = (int) (srcByteOffset / pageBytes);
        long slot = slab.slots[page];
        Chunk chunk = chunks.get(chunkIndex(slot));
        chunk.queueCopy(offset(slot) + srcByteOffset - page * pageBytes, dstByteOffset, byteSize);
    }

    /**
     * Flushes the frame's slab writes and scatters the queued page copies into {@code dstVbo}.
     */
    void flush(int dstVbo) {
        boolean bound = false;
        for (Chunk chunk : chunks) {
            if (chunk.flushEnd > chunk.flushStart) {
                GL45C.glFlushMappedNamedBufferRange(chunk.handle, chunk.flushStart, chunk.flushEnd - chunk.flushStart);
                chunk.flushStart = Long.MAX_VALUE;
                chunk.flushEnd = 0;
            }
            if (chunk.copyCount == 0) {
                continue;
            }
            if (!bound) {
                programs.getScatterProgram()
                        .bind();
                GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, dstVbo);
                bound = true;
            }
            copyBuffer.upload(chunk.copies, chunk.copyCount * COPY_STRIDE);
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, copyBuffer.handle());
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, chunk.handle);
            GL43C.glDispatchCompute(chunk.copyCount, 1, 1);
            chunk.copyCount = 0;
        }
    }

    void delete() {
        for (Chunk chunk : chunks) {
            chunk.delete();
        }
        chunks.clear();
        freeSlots.clear();
        retiredThisFrame.clear();
        retiredLastFrame.clear();
        copyBuffer.delete();
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

    private static int chunkIndex(long slot) {
        return (int) (slot >>> 32);
    }

    private static long offset(long slot) {
        return slot & 0xFFFFFFFFL;
    }

    private static final class Chunk {
        private final int handle;
        private final long ptr;
        private final long capacity;
        private long copies;
        private int copyCapacity;
        private int copyCount;
        private long top;
        private long flushStart = Long.MAX_VALUE;
        private long flushEnd;

        private Chunk(long capacity) {
            this.capacity = capacity;
            long bytes = capacity + TAIL_BYTES;
            handle = GL45C.glCreateBuffers();
            GL45C.glNamedBufferStorage(handle, bytes, STORAGE_FLAGS);
            ptr = GL45C.nglMapNamedBufferRange(handle, 0, bytes, MAP_FLAGS);
            FlwMemoryTracker._allocGpuMemory(bytes);
        }

        private void queueCopy(long src, long dst, long bytes) {
            int n = (int) ((bytes + COPY_BYTES - 1) / COPY_BYTES);
            if (copyCount + n > copyCapacity) {
                copyCapacity = Math.max(copyCount + n, copyCapacity * 2);
                copies = MemoryUtil.nmemRealloc(copies, copyCapacity * COPY_STRIDE);
            }
            long ptr = copies + copyCount * COPY_STRIDE;
            for (long done = 0; done < bytes; done += COPY_BYTES, ptr += COPY_STRIDE) {
                long size = Math.min(COPY_BYTES, bytes - done);
                MemoryUtil.memPutInt(ptr, (int) ((size >>> 2) << 24 | ((src + done) >>> 2)));
                MemoryUtil.memPutInt(ptr + Integer.BYTES, (int) ((dst + done) >>> 2));
            }
            copyCount += n;
        }

        private void markWritten(long start, long end) {
            flushStart = Math.min(flushStart, start);
            flushEnd = Math.max(flushEnd, end);
        }

        private void delete() {
            GL45C.glUnmapNamedBuffer(handle);
            GL15C.glDeleteBuffers(handle);
            FlwMemoryTracker._freeGpuMemory(capacity + TAIL_BYTES);
            MemoryUtil.nmemFree(copies);
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
            int first = (int) (byteOffset / pageBytes);
            int last = (int) ((byteOffset + byteSize - 1) / pageBytes);
            for (int page = first; page <= last; page++) {
                long slot = slots[page];
                long start = offset(slot);
                chunks.get(chunkIndex(slot))
                      .markWritten(start, start + pageBytes);
            }
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
