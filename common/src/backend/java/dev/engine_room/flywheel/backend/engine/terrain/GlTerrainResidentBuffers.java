package dev.engine_room.flywheel.backend.engine.terrain;

import dev.engine_room.flywheel.backend.engine.indirect.ResizableStorageArray;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlResidentBuffer;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class GlTerrainResidentBuffers implements TerrainResidentBuffers {
    private final StagingBuffer staging;
    private final List<Mirror> mirrors = new ArrayList<>();

    public GlTerrainResidentBuffers(StagingBuffer staging) {
        this.staging = staging;
    }

    private static void clear(int handle, long offset, long size, int fillWord, int[] scratch) {
        scratch[0] = fillWord;
        GL45.glClearNamedBufferSubData(handle, GL30.GL_R32UI, offset, size, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT,
                scratch);
    }

    @Override
    public TerrainResidentBuffer createMirror(long stride) {
        Mirror mirror = new Mirror(stride);
        mirrors.add(mirror);
        return mirror;
    }

    @Override
    public TerrainResidentBuffer createDynamic() {
        return new Dynamic();
    }

    @Override
    public void flushPendingWrites() {
        boolean staged = false;
        for (Mirror mirror : mirrors) {
            staged |= mirror.stagePending();
        }
        if (staged) {
            staging.flush();
        }
    }

    @Override
    public void flushBeforeGrow() {
        flushPendingWrites();
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
    }

    private static final class Dynamic implements TerrainResidentBuffer {
        private final GlResidentBuffer buffer = new GlResidentBuffer();
        private final int[] clearScratch = new int[1];

        @Override
        public boolean ensureCapacity(long bytes) {
            return buffer.ensureCapacity(bytes);
        }

        @Override
        public long byteCapacity() {
            return buffer.capacity();
        }

        @Override
        public void write(long offset, long srcPtr, long size) {
            buffer.uploadSpan(offset, srcPtr, size);
        }

        @Override
        public void clearRange(long offset, long size, int fillWord) {
            clear(buffer.handle(), offset, size, fillWord, clearScratch);
        }

        @Override
        public long deviceAddress() {
            return buffer.deviceAddress();
        }

        @Override
        public int handle() {
            return buffer.handle();
        }

        @Override
        public void delete() {
            buffer.delete();
        }
    }

    // Writes are held per slot and staged once at flush: StagingBuffer's scatter runs one flush's copies into a
    // buffer in parallel, so two copies to the same slot race and the older bytes can win.
    private final class Mirror implements TerrainResidentBuffer {
        private final ResizableStorageArray array;
        private final long stride;
        private final int[] clearScratch = new int[1];
        private final Long2IntOpenHashMap pendingIndexByOffset = new Long2IntOpenHashMap();
        private long[] pendingOffsets = new long[64];
        private MemoryBlock pendingBytes;
        private int pendingCount;

        Mirror(long stride) {
            this.stride = stride;
            this.array = new ResizableStorageArray(stride);
            this.pendingBytes = MemoryBlock.malloc(pendingOffsets.length * stride);
            pendingIndexByOffset.defaultReturnValue(-1);
        }

        private boolean stagePending() {
            if (pendingCount == 0) {
                return false;
            }
            int handle = array.handle();
            for (int i = 0; i < pendingCount; i++) {
                long src = pendingBytes.ptr() + i * stride;
                staging.enqueueCopy(stride, handle, pendingOffsets[i], ptr -> MemoryUtil.memCopy(src, ptr, stride));
            }
            pendingCount = 0;
            pendingIndexByOffset.clear();
            return true;
        }

        private void dropPending(long offset) {
            int index = pendingIndexByOffset.remove(offset);
            if (index < 0) {
                return;
            }
            int last = --pendingCount;
            if (index != last) {
                long moved = pendingOffsets[last];
                pendingOffsets[index] = moved;
                pendingIndexByOffset.put(moved, index);
                MemoryUtil.memCopy(pendingBytes.ptr() + last * stride, pendingBytes.ptr() + index * stride, stride);
            }
        }

        @Override
        public boolean ensureCapacity(long bytes) {
            long before = array.byteCapacity();
            array.ensureCapacity((bytes + stride - 1) / stride);
            return array.byteCapacity() != before;
        }

        @Override
        public long byteCapacity() {
            return array.byteCapacity();
        }

        @Override
        public void write(long offset, long srcPtr, long size) {
            assert size == stride && offset % stride == 0;
            int index = pendingIndexByOffset.get(offset);
            if (index < 0) {
                index = pendingCount++;
                if (index == pendingOffsets.length) {
                    pendingOffsets = Arrays.copyOf(pendingOffsets, index * 2);
                    pendingBytes = pendingBytes.realloc(pendingOffsets.length * stride);
                }
                pendingOffsets[index] = offset;
                pendingIndexByOffset.put(offset, index);
            }
            MemoryUtil.memCopy(srcPtr, pendingBytes.ptr() + index * stride, stride);
        }

        @Override
        public void clearRange(long offset, long size, int fillWord) {
            for (long slot = offset; slot < offset + size; slot += stride) {
                dropPending(slot);
            }
            clear(array.handle(), offset, size, fillWord, clearScratch);
        }

        @Override
        public long deviceAddress() {
            return array.deviceAddress();
        }

        @Override
        public int handle() {
            return array.handle();
        }

        @Override
        public void delete() {
            array.delete();
            pendingBytes.free();
            mirrors.remove(this);
        }
    }
}
