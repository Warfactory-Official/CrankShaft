package dev.engine_room.flywheel.backend.engine.terrain;

import dev.engine_room.flywheel.backend.engine.indirect.ResizableStorageArray;
import dev.engine_room.flywheel.backend.engine.indirect.StagingBuffer;
import dev.engine_room.flywheel.backend.gl.buffer.GlResidentBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

public final class GlTerrainResidentBuffers implements TerrainResidentBuffers {
    private final StagingBuffer staging;
    private boolean pending;

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
        return new Mirror(stride);
    }

    @Override
    public TerrainResidentBuffer createDynamic() {
        return new Dynamic();
    }

    @Override
    public void flushPendingWrites() {
        if (pending) {
            staging.flush();
            pending = false;
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

    private final class Mirror implements TerrainResidentBuffer {
        private final ResizableStorageArray array;
        private final long stride;
        private final int[] clearScratch = new int[1];

        Mirror(long stride) {
            this.stride = stride;
            this.array = new ResizableStorageArray(stride);
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
            staging.enqueueCopy(size, array.handle(), offset, ptr -> MemoryUtil.memCopy(srcPtr, ptr, size));
            pending = true;
        }

        @Override
        public void clearRange(long offset, long size, int fillWord) {
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
        }
    }
}
