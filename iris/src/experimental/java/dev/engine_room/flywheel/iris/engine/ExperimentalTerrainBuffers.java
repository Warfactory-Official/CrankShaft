package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainResidentBuffer;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainResidentBuffers;
import net.irisshaders.iris.Iris;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Resident metadata uploads shared by main and shadow terrain.
 */
final class ExperimentalTerrainBuffers implements TerrainResidentBuffers {
    private final List<Buffer> buffers = new ArrayList<>();

    @Override
    public TerrainResidentBuffer createMirror(long stride) {
        Buffer buffer = new Buffer(Math.toIntExact(stride));
        buffers.add(buffer);
        return buffer;
    }

    @Override
    public TerrainResidentBuffer createDynamic() {
        return createMirror(4);
    }

    @Override
    public void flushPendingWrites() {
        buffers.forEach(Buffer::flush);
    }

    @Override
    public void flushBeforeGrow() {
        flushPendingWrites();
    }

    private static final class Buffer implements TerrainResidentBuffer {
        private static final int PAGE = 1024;
        private final BitSet dirty = new BitSet();
        private ByteBuffer shadow;
        private GpuBuffer gpu;

        Buffer(int bytes) {
            shadow = MemoryUtil.memCalloc(Math.max(4, bytes)).order(ByteOrder.nativeOrder());
            gpu = Iris.getRenderBackend()
                      .createStorageBuffer("flywheel guest terrain metadata", shadow.capacity(), null);
            dirty.set(0);
        }

        @Override
        public boolean ensureCapacity(long bytes) {
            if (bytes <= shadow.capacity()) return false;
            int size = Math.toIntExact(Math.max(bytes, shadow.capacity() * 2L));
            ByteBuffer next = MemoryUtil.memCalloc(size).order(ByteOrder.nativeOrder());
            next.put(0, shadow, 0, shadow.capacity());
            MemoryUtil.memFree(shadow);
            shadow = next;
            GpuBuffer old = gpu;
            gpu = Iris.getRenderBackend().createStorageBuffer("flywheel guest terrain metadata", size, null);
            Iris.getRenderBackend().retire(old::close);
            dirty.set(0, (size + PAGE - 1) / PAGE);
            return true;
        }

        @Override
        public long byteCapacity() {
            return shadow.capacity();
        }

        @Override
        public void write(long offset, long srcPtr, long size) {
            int start = Math.toIntExact(offset);
            int count = Math.toIntExact(size);
            shadow.put(start, MemoryUtil.memByteBuffer(srcPtr, count), 0, count);
            mark(start, count);
        }

        @Override
        public void clearRange(long offset, long size, int fillWord) {
            int start = Math.toIntExact(offset);
            int count = Math.toIntExact(size);
            if (fillWord == 0) MemoryUtil.memSet(MemoryUtil.memAddress(shadow) + start, 0, count);
            else for (int i = start; i < start + count; i += 4) shadow.putInt(i, fillWord);
            mark(start, count);
        }

        private void mark(int offset, int size) {
            if (size > 0) dirty.set(offset / PAGE, (offset + size - 1) / PAGE + 1);
        }

        void flush() {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            for (int first = dirty.nextSetBit(0); first >= 0; ) {
                int end = dirty.nextClearBit(first);
                int start = first * PAGE;
                int count = Math.min(shadow.capacity(), end * PAGE) - start;
                encoder.writeToBuffer(gpu.slice(start, count), shadow.slice(start, count));
                first = dirty.nextSetBit(end);
            }
            dirty.clear();
        }

        @Override
        public GpuBufferSlice slice() {
            return gpu.slice();
        }

        @Override
        public void delete() {
            Iris.getRenderBackend().retire(gpu::close);
            MemoryUtil.memFree(shadow);
        }
    }
}
