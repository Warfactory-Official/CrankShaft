package dev.engine_room.flywheel.iris.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedInstancer;

import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Shared slab/handle lifecycle with Iris-native uploads.
 */
public final class ExperimentalInstancer<I extends Instance> extends InstancedInstancer<I> {
    final Vector4fc bounds;
    final int typeId;
    private final ExperimentalInstanceArena arena;
    private final ByteBuffer rangeData = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
    GpuBufferSlice range;
    private long allocatedBytes;
    private long offset;
    private boolean uploaded;

    ExperimentalInstancer(InstancerKey<I> key, Recreate<I> recreate, ExperimentalInstanceArena arena) {
        super(key, recreate);
        this.arena = arena;
        bounds = key.model().boundingSphere();
        typeId = InstanceTypeIds.id(type);
    }

    int strideWords() {
        return Math.toIntExact(instanceStride / Integer.BYTES);
    }

    int baseWord() {
        return Math.toIntExact(offset / Integer.BYTES);
    }

    void range(int visibleBase) {
        rangeData.clear().putInt(baseWord()).putInt(visibleBase).putInt(0).putInt(0).flip();
        range = RenderSystem.getDevice().createCommandEncoder().transientMemory().uploadGpu(rangeData,
                RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
    }

    @Override
    public GpuBuffer instanceTexels() {
        return arena.buffer();
    }

    @Override
    public void updateBuffer() {
        if (prepareUpload() == null) return;
        long bytes = Math.multiplyExact(instanceCount(), instanceStride);
        if (bytes == 0) return;
        if (allocatedBytes < bytes) {
            if (allocatedBytes != 0) arena.free(offset, allocatedBytes);
            allocatedBytes = Math.max(bytes, Math.max(256, allocatedBytes * 2));
            offset = arena.allocate(allocatedBytes);
            uploaded = false;
        }
        if (!uploaded) {
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(arena.buffer().slice(offset, bytes),
                    MemoryUtil.memByteBuffer(slabBlocks[0], Math.toIntExact(bytes)));
            changed.clear();
            uploaded = true;
        } else if (!changed.isEmpty()) {
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            for (int start = changed.nextSetBit(0); start >= 0; ) {
                int end = Math.min(instanceCount(), changed.nextClearBit(start));
                long begin = start * instanceStride;
                long size = (end - start) * instanceStride;
                encoder.writeToBuffer(arena.buffer().slice(offset + begin, size),
                        MemoryUtil.memByteBuffer(slabBlocks[0] + begin, Math.toIntExact(size)));
                start = changed.nextSetBit(end);
            }
            changed.clear();
        }
    }

    @Override
    public void prepareInstanceTexels() {
        updateBuffer();
    }

    @Override
    public void delete() {
        if (allocatedBytes != 0) {
            arena.free(offset, allocatedBytes);
            allocatedBytes = 0;
        }
        super.delete();
    }
}
