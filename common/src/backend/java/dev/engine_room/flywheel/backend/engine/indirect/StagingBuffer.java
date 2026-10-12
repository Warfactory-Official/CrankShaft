package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.gl.GlFence;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlBufferUsage;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import it.unimi.dsi.fastutil.PriorityQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.function.LongConsumer;
import java.util.ArrayList;
import java.util.List;

// Used https://github.com/CaffeineMC/sodium-fabric/blob/dev/src/main/java/me/jellysquid/mods/sodium/client/gl/arena/staging/MappedStagingBuffer.java
// as a reference for implementation. Modified to be less safe and to allow for writing directly into the staging buffer.
public class StagingBuffer {
    private static final long DEFAULT_CAPACITY = 1024 * 1024 * 16;
    // Port: scatter source offsets are 24-bit uint indices.
    private static final long MAX_SOURCE_BYTES = 1L << 26;
    // Port: scatter.glsl fetches a full workgroup row before checking the copy size.
    private static final long READ_AHEAD_BYTES = 64L * Integer.BYTES;
    private static final int STORAGE_FLAGS = GL44C.GL_MAP_PERSISTENT_BIT | GL30C.GL_MAP_WRITE_BIT | GL44C.GL_CLIENT_STORAGE_BIT;
    private static final int MAP_FLAGS = GL44C.GL_MAP_PERSISTENT_BIT | GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_FLUSH_EXPLICIT_BIT | GL30C.GL_MAP_INVALIDATE_BUFFER_BIT;

    private static final int SSBO_ALIGNMENT = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
    private static final int MAX_SCATTER_GROUPS = maxScatterGroups();

    private static int maxScatterGroups() {
        int limit = GL30C.glGetIntegeri(GL43C.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0);
        int alignedCopies = Math.max(1, SSBO_ALIGNMENT / (int) ScatterList.STRIDE);
        if (limit < alignedCopies) {
            throw new IllegalStateException("SSBO alignment exceeds scatter dispatch capacity");
        }
        return limit - limit % alignedCopies;
    }

    private final int vbo;
    private final long map;
    private final long capacity;
    private final IndirectPrograms programs;

    private final OverflowStagingBuffer overflow = new OverflowStagingBuffer();
    private final BatchedStagingBuffer batches = new BatchedStagingBuffer();
    private final SourcePool sourcePool = new SourcePool();
    private final List<SourceBuffer> pendingSources = new ArrayList<>();
    private static final SourceBuffer[] NO_SOURCES = new SourceBuffer[0];
    private final TransferList transfers = new TransferList();
    private final PriorityQueue<FencedRegion> fencedRegions = new ObjectArrayFIFOQueue<>();
    private final GlBuffer scatterBuffer = new GlBuffer(GlBufferUsage.STREAM_COPY);
    private final ScatterList scatterList = new ScatterList();

    /**
     * The position in the buffer at the time of the last flush.
     */
    private long start = 0;
    /**
     * The current position in the buffer,
     * incremented as transfers are enqueued.
     */
    private long pos = 0;
    /**
     * The number of bytes used in the buffer since the last flush,
     * incremented as transfers are enqueued.
     */
    private long usedCapacity = 0;

    /**
     * The number of bytes available in the buffer.
     * <br>
     * This decreases as transfers are enqueued and increases as fenced regions are reclaimed.
     */
    private long totalAvailable;

    /**
     * A scratch buffer for when there is not enough contiguous space
     * in the staging buffer for the write the user wants to make.
     */
    @Nullable
    private MemoryBlock scratch;

    public StagingBuffer(IndirectPrograms programs) {
        this(DEFAULT_CAPACITY, programs);
    }

    public StagingBuffer(long capacity, IndirectPrograms programs) {
        if (capacity <= 0 || capacity > MAX_SOURCE_BYTES || capacity % Integer.BYTES != 0) {
            throw new IllegalArgumentException("Staging capacity must be a positive multiple of 4 up to 64 MiB");
        }
        this.capacity = capacity;
        this.programs = programs;
        vbo = GL45C.glCreateBuffers();

        GL45C.glNamedBufferStorage(vbo, capacity + READ_AHEAD_BYTES, STORAGE_FLAGS);
        map = mapBuffer(vbo, capacity + READ_AHEAD_BYTES);

        totalAvailable = capacity;

        FlwMemoryTracker._allocCpuMemory(capacity + READ_AHEAD_BYTES);
    }

    private static long mapBuffer(int buffer, long bytes) {
        long ptr = GL45C.nglMapNamedBufferRange(buffer, 0, bytes, MAP_FLAGS);
        if (ptr == MemoryUtil.NULL) {
            int error = GL11C.glGetError();
            if (error == GL11C.GL_OUT_OF_MEMORY) throw new OutOfMemoryError("Mapping GL upload buffer of " + bytes + " bytes");
            throw new IllegalStateException("Mapping GL upload buffer failed with error " + error);
        }
        return ptr;
    }

    /**
     * Enqueue a copy of a known size to the given VBO.
     * <br>
     * The consumer will receive a pointer to a block of memory of the given size, and is expected to write to the
     * complete range. The initial contents of the memory block are undefined.
     *
     * @param size      The size in bytes of the copy.
     * @param dstVbo    The VBO to copy to.
     * @param dstOffset The offset in the destination VBO.
     * @param write     A consumer that will receive a pointer to the memory block.
     */
    public void enqueueCopy(long size, int dstVbo, long dstOffset, LongConsumer write) {
        // Try to write directly into the staging buffer if there is enough contiguous space.
        var direct = reserveForCopy(size, dstVbo, dstOffset);

        if (direct != MemoryUtil.NULL) {
            write.accept(direct);
            return;
        }

        // Otherwise, write to a scratch buffer and enqueue a copy.
        var block = getScratch(size);
        write.accept(block.ptr());
        enqueueCopy(block.ptr(), size, dstVbo, dstOffset);
    }

    /**
     * Enqueue a copy from the given pointer to the given VBO.
     *
     * @param ptr       The pointer to copy from.
     * @param size      The size of the copy.
     * @param dstVbo    The VBO to copy to.
     * @param dstOffset The offset in the destination VBO.
     */
    public void enqueueCopy(long ptr, long size, int dstVbo, long dstOffset) {
        assertMultipleOf4(size);

        if (size > totalAvailable) {
            overflow.upload(ptr, size, dstVbo, dstOffset);
            return;
        }

        long remaining = capacity - pos;

        if (size > remaining) {
            long split = size - remaining;

            // Put the first span at the tail of the buffer...
            MemoryUtil.memCopy(ptr, map + pos, remaining);
            pushTransfer(dstVbo, pos, dstOffset, remaining);

            // ... and the rest at the head.
            MemoryUtil.memCopy(ptr + remaining, map, split);
            pushTransfer(dstVbo, 0, dstOffset + remaining, split);

            pos = split;
        } else {
            MemoryUtil.memCopy(ptr, map + pos, size);
            pushTransfer(dstVbo, pos, dstOffset, size);

            pos += size;
        }
    }

    /**
     * Snapshots small, scattered CPU ranges into cached memory for one bulk buffer upload at flush.
     */
    void enqueueBatchedCopy(long ptr, long size, int dstVbo, long dstOffset) {
        assertMultipleOf4(size);
        batches.upload(ptr, size, dstVbo, dstOffset);
    }

    /**
     * Reserve space in this buffer for a transfer to another VBO.
     * <br>
     * You must ensure that your writes are complete before the next call to {@link #flush}.
     * <br>
     * This will generally be a more efficient way to transfer data as it avoids a copy, however,
     * this method does not allow for non-contiguous writes, so you should fall back to
     * {@link #enqueueCopy} if this returns {@code null}.
     *
     * @param size      The size of the transfer you wish to make.
     * @param dstVbo    The VBO you wish to transfer to.
     * @param dstOffset The offset in the destination VBO.
     * @return A pointer to the reserved space, or {@code null} if there is not enough contiguous space.
     */
    public long reserveForCopy(long size, int dstVbo, long dstOffset) {
        assertMultipleOf4(size);
        long remaining = capacity - pos;
        if (size > remaining || size > totalAvailable) {
            return MemoryUtil.NULL;
        }

        long out = map + pos;

        pushTransfer(dstVbo, pos, dstOffset, size);

        pos += size;

        return out;
    }

    public void flush() {
        if (transfers.isEmpty() && batches.isEmpty() && usedCapacity == 0 && pendingSources.isEmpty()) {
            return;
        }

        flushUsedRegion();

        if (!transfers.isEmpty()) {
            dispatchComputeCopies(transfers, vbo);
        }
        batches.flush();

        transfers.reset();
        if (usedCapacity > 0 || !pendingSources.isEmpty()) {
            SourceBuffer[] sources = pendingSources.isEmpty() ? NO_SOURCES : pendingSources.toArray(NO_SOURCES);
            pendingSources.clear();
            fencedRegions.enqueue(new FencedRegion(new GlFence(), usedCapacity, sources));
        }

        usedCapacity = 0;
        start = pos;
    }

    public void reclaim() {
        while (!fencedRegions.isEmpty()) {
            var region = fencedRegions.first();
            if (!region.fence.isSignaled()) {
                // We can't reclaim this region yet, and we know that all the regions after it are also not ready.
                break;
            }
            fencedRegions.dequeue();

            region.fence.delete();

            totalAvailable += region.capacity;
            for (SourceBuffer source : region.sources) sourcePool.release(source);
        }
    }

    public void delete() {
        GL45C.glUnmapNamedBuffer(vbo);
        GL15C.glDeleteBuffers(vbo);
        overflow.delete();
        batches.delete();
        scatterBuffer.delete();

        if (scratch != null) {
            scratch.free();
        }

        transfers.delete();
        scatterList.delete();

        while (!fencedRegions.isEmpty()) {
            fencedRegions.dequeue().fence().delete();
        }
        sourcePool.delete();
        pendingSources.clear();

        FlwMemoryTracker._freeCpuMemory(capacity + READ_AHEAD_BYTES);
    }

    public MemoryBlock getScratch(long size) {
        if (scratch == null) {
            scratch = MemoryBlock.malloc(size);
        } else if (scratch.size() < size) {
            scratch = scratch.realloc(size);
        }
        return scratch;
    }

    private void pushTransfer(int dstVbo, long srcOffset, long dstOffset, long size) {
        if (totalAvailable < size) {
            throw new IllegalStateException("Not enough available space to transfer");
        }

        transfers.push(dstVbo, srcOffset, dstOffset, size);
        usedCapacity += size;
        totalAvailable -= size;
    }

    private void dispatchComputeCopies(TransferList transfers, int srcVbo) {
        programs.getScatterProgram().bind();

        // These bindings don't change between dstVbos.
        GL30.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 1, srcVbo);

        int dstVbo;
        var transferCount = transfers.length();
        for (int i = 0; i < transferCount; i++) {
            dstVbo = transfers.vbo(i);

            scatterList.pushTransfer(transfers, i);

            int nextVbo = i == transferCount - 1 ? -1 : transfers.vbo(i + 1);

            // If we're switching VBOs, dispatch the copies for the previous VBO.
            // Generally VBOs don't appear in multiple spans of the list,
            // so submitting duplicates is rare.
            if (dstVbo != nextVbo) {
                dispatchScatter(dstVbo);
            }
        }
    }

    private void dispatchScatter(int dstVbo) {
        var scatterSize = scatterList.usedBytes();

        // If there's enough space in the staging buffer still, lets write the scatter in it directly.
        long alignedPos = pos + SSBO_ALIGNMENT - 1 - (pos + SSBO_ALIGNMENT - 1) % SSBO_ALIGNMENT;
        long alignmentCost = alignedPos - pos;

        long remaining = capacity - alignedPos;
        int copiesVbo;
        long copiesOffset;
        if (scatterSize <= remaining && scatterSize + alignmentCost <= totalAvailable) {
            MemoryUtil.memCopy(scatterList.ptr(), map + alignedPos, scatterSize);
            GL45C.glFlushMappedNamedBufferRange(vbo, alignedPos, scatterSize);
            copiesVbo = vbo;
            copiesOffset = alignedPos;

            usedCapacity += scatterSize + alignmentCost;
            totalAvailable -= scatterSize + alignmentCost;
            pos += scatterSize + alignmentCost;
        } else {
            scatterBuffer.upload(scatterList.ptr(), scatterSize);
            copiesVbo = scatterBuffer.handle();
            copiesOffset = 0;
        }

        GL30.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 2, dstVbo);

        // Port: slicing the copy list keeps each dispatch within the device limit and each SSBO offset aligned.
        for (int first = 0; first < scatterList.copyCount(); first += MAX_SCATTER_GROUPS) {
            int count = Math.min(MAX_SCATTER_GROUPS, scatterList.copyCount() - first);
            GL30.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, 0, copiesVbo,
                    copiesOffset + first * ScatterList.STRIDE, count * ScatterList.STRIDE);
            GL43.glDispatchCompute(count, 1, 1);
        }

        scatterList.reset();
    }

    private void assertMultipleOf4(long size) {
        if (size % 4 != 0) {
            throw new IllegalArgumentException("Size must be a multiple of 4");
        }
    }

    private void flushUsedRegion() {
        // Port: equal cursors can also mean a full ring was written.
        long firstSpan = Math.min(usedCapacity, capacity - start);
        if (firstSpan > 0) {
            GL45C.glFlushMappedNamedBufferRange(vbo, start, firstSpan);
        }
        long secondSpan = usedCapacity - firstSpan;
        if (secondSpan > 0) {
            GL45C.glFlushMappedNamedBufferRange(vbo, 0, secondSpan);
        }
    }

    private record FencedRegion(GlFence fence, long capacity, SourceBuffer[] sources) {
    }

    private static final class SourcePool {
        private static final int MIN_SHIFT = 16;
        @SuppressWarnings("unchecked")
        private final List<SourceBuffer>[] free = (List<SourceBuffer>[]) new List<?>[27 - MIN_SHIFT];
        private final List<SourceBuffer> buffers = new ArrayList<>();
        private long allocatedBytes;
        private long busyBytes;
        private long retainedBytes;
        private int lastBucket;

        private SourceBuffer acquire(long bytes) {
            int bucket = Math.max(0, Long.SIZE - Long.numberOfLeadingZeros(bytes - 1) - MIN_SHIFT);
            List<SourceBuffer> available = free[bucket];
            SourceBuffer source;
            if (available != null && !available.isEmpty()) {
                source = available.removeLast();
            } else {
                source = new SourceBuffer(bucket, (1L << (MIN_SHIFT + bucket)) + READ_AHEAD_BYTES);
                buffers.add(source);
                allocatedBytes += source.capacity;
            }
            lastBucket = bucket;
            busyBytes += source.capacity;
            retainedBytes = Math.max(retainedBytes, Math.max(busyBytes, source.capacity * 2));
            return source;
        }

        private void release(SourceBuffer source) {
            List<SourceBuffer> available = free[source.bucket];
            if (available == null) free[source.bucket] = available = new ArrayList<>();
            available.add(source);
            busyBytes -= source.capacity;
            if (allocatedBytes <= retainedBytes) return;
            // Port: retain observed concurrency and a spare largest buffer, not every historical size class.
            for (int pass = 0; pass < 2 && allocatedBytes > retainedBytes; pass++) {
                for (int bucket = 0; bucket < free.length && allocatedBytes > retainedBytes; bucket++) {
                    if ((bucket == lastBucket) != (pass == 1)) continue;
                    List<SourceBuffer> candidates = free[bucket];
                    if (candidates == null) continue;
                    while (!candidates.isEmpty() && allocatedBytes > retainedBytes) {
                        SourceBuffer unused = candidates.removeLast();
                        unused.delete();
                        buffers.remove(unused);
                        allocatedBytes -= unused.capacity;
                    }
                }
            }
        }

        private void delete() {
            for (SourceBuffer source : buffers) source.delete();
            buffers.clear();
        }
    }

    private static final class SourceBuffer {
        private final int bucket;
        private final int handle;
        private final long map;
        private final long capacity;

        private SourceBuffer(int bucket, long capacity) {
            this.bucket = bucket;
            this.capacity = capacity;
            handle = GL45C.glCreateBuffers();
            // Port: write-only GPU scatter sources retain the arena's GPU-preferred allocation hint.
            GL45C.glNamedBufferStorage(handle, capacity, STORAGE_FLAGS & ~GL44C.GL_CLIENT_STORAGE_BIT);
            map = mapBuffer(handle, capacity);
            FlwMemoryTracker._allocGpuMemory(capacity);
        }

        private void delete() {
            GL45C.glUnmapNamedBuffer(handle);
            GL15C.glDeleteBuffers(handle);
            FlwMemoryTracker._freeGpuMemory(capacity);
        }
    }

    private class BatchedStagingBuffer {
        private final TransferList transfers = new TransferList();
        @Nullable
        private MemoryBlock data;
        private long length;

        public void upload(long ptr, long size, int dstVbo, long dstOffset) {
            while (size > 0) {
                long bytes = Math.min(size, MAX_SOURCE_BYTES - length);
                long needed = length + bytes + READ_AHEAD_BYTES;
                if (data == null) {
                    data = MemoryBlock.mallocTracked(Math.max(1L << 20, needed));
                } else if (data.size() < needed) {
                    data = data.realloc(Math.max(needed,
                            Math.min(MAX_SOURCE_BYTES + READ_AHEAD_BYTES, data.size() * 2)));
                }
                MemoryUtil.memCopy(ptr, data.ptr() + length, bytes);
                transfers.push(dstVbo, length, dstOffset, bytes);
                length += bytes;
                ptr += bytes;
                dstOffset += bytes;
                size -= bytes;
                if (length == MAX_SOURCE_BYTES) {
                    flush();
                }
            }
        }

        private boolean isEmpty() {
            return transfers.isEmpty();
        }

        private void flush() {
            if (transfers.isEmpty()) {
                return;
            }
            long bytes = length + READ_AHEAD_BYTES;
            SourceBuffer source = sourcePool.acquire(length);
            pendingSources.add(source);
            MemoryUtil.memCopy(data.ptr(), source.map, bytes);
            GL45C.glFlushMappedNamedBufferRange(source.handle, 0, bytes);
            dispatchComputeCopies(transfers, source.handle);
            transfers.reset();
            length = 0;
        }

        public void delete() {
            transfers.delete();
            if (data != null) {
                data.free();
            }
        }
    }

    private static class OverflowStagingBuffer {
        private final int vbo;
        private long currentSize;

        public OverflowStagingBuffer() {
            vbo = GL45C.glCreateBuffers();
        }

        public void upload(long ptr, long size, int dstVbo, long dstOffset) {
            FlwMemoryTracker._freeGpuMemory(currentSize);
            GL45C.nglNamedBufferData(vbo, size, ptr, GL15C.GL_STREAM_COPY);
            currentSize = size;
            FlwMemoryTracker._allocGpuMemory(size);
            GL45C.glCopyNamedBufferSubData(vbo, dstVbo, 0, dstOffset, size);
        }

        public void delete() {
            GL15C.glDeleteBuffers(vbo);
            FlwMemoryTracker._freeGpuMemory(currentSize);
            currentSize = 0;
        }
    }
}
