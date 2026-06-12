package dev.engine_room.flywheel.backend.engine.instancing;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.engine.BaseInstancer;
import dev.engine_room.flywheel.backend.engine.GlSlab;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static dev.engine_room.flywheel.backend.engine.EngineConstants.LOG_2_PAGE_SIZE;
import static dev.engine_room.flywheel.backend.engine.EngineConstants.PAGE_MASK;

public class InstancedInstancer<I extends Instance> extends BaseInstancer<I> {
    private static final int PAGE_SIZE = PAGE_MASK + 1;
    @Nullable
    private static ByteBuffer texelStaging;
    private final List<InstancedDraw> draws = new ArrayList<>();
    @Nullable
    private GpuBuffer instanceTexels;
    private int texelCapacity;
    private boolean texelsReady;
    private boolean texelsValid;

    public InstancedInstancer(InstancerKey<I> key, Recreate<I> recreate) {
        super(key, recreate);
    }

    private static ByteBuffer acquireTexelStaging(int bytes) {
        ByteBuffer buf = texelStaging;
        if (buf == null || buf.capacity() < bytes) {
            if (buf != null) {
                MemoryUtil.memFree(buf);
            }
            buf = MemoryUtil.memAlloc(Math.max(bytes, buf == null ? bytes : buf.capacity() * 2));
            texelStaging = buf;
        }
        buf.clear();
        buf.limit(bytes);
        return buf;
    }

    @Nullable
    public GpuBuffer instanceTexels() {
        return instanceTexels;
    }

    public void resetTexelsReady() {
        texelsReady = false;
    }

    public void prepareInstanceTexels() {
        if (texelsReady) {
            return;
        }
        int count = instanceCount();
        if (count == 0) {
            return;
        }

        boolean needsGrow = instanceTexels == null || texelCapacity < count;
        if (texelsValid && !needsGrow) {
            texelsReady = true;
            return;
        }

        long needBytes = (long) count * instanceStride;
        ByteBuffer staging = acquireTexelStaging((int) needBytes);
        long dstBase = MemoryUtil.memAddress(staging);
        long[] blocks = slabBlocks;
        for (int base = 0; base < count; base += PAGE_SIZE) {
            int n = Math.min(PAGE_SIZE, count - base);
            MemoryUtil.memCopy(blocks[base >>> LOG_2_PAGE_SIZE], dstBase + (long) base * instanceStride,
                    (long) n * instanceStride);
        }

        if (needsGrow) {
            if (instanceTexels != null) {
                instanceTexels.close();
            }
            instanceTexels = RenderSystem.getDevice()
                                         .createBuffer(() -> "flywheel instances",
                                                 GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST,
                                                 needBytes);
            texelCapacity = count;
        }
        RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(instanceTexels.slice(0L, needBytes), staging);
        texelsValid = true;
        texelsReady = true;
    }

    public List<InstancedDraw> draws() {
        return draws;
    }

    public void init() {
    }

    public void updateBuffer() {
        GlSlab buf = prepareUpload();
        if (buf == null || changed.isEmpty()) {
            return;
        }
        texelsValid = false;

        int size = instances.size();
        long stride = instanceStride;
        long maxByte = (long) size * stride;

        changed.forEachSetSpan((startInclusive, endInclusive) -> {
            if (startInclusive >= size) {
                return;
            }
            int actualEnd = Math.min(endInclusive, size - 1);
            long byteStart = (long) startInclusive * stride;
            long byteSize = ((long) (actualEnd - startInclusive + 1)) * stride;
            if (byteStart + byteSize > maxByte) {
                byteSize = maxByte - byteStart;
            }
            buf.flushRange(byteStart, byteSize);
        });

        changed.clear();
    }

    @Override
    public void parallelUpdate() {
        if (deleted.isEmpty()) {
            return;
        }

        final int oldSize = this.instances.size();
        int removeCount = deleted.cardinality();

        if (oldSize == removeCount) {
            clear();
            return;
        }

        final int newSize = oldSize - removeCount;

        int writePos = deleted.nextSetBit(0);

        if (writePos < newSize) {
            changed.set(writePos, newSize);
        }

        changed.clear(newSize, oldSize);

        long[] blocks = slabBlocks;
        for (int scanPos = writePos; (scanPos < oldSize) && (writePos < newSize); scanPos++, writePos++) {
            scanPos = deleted.nextClearBit(scanPos);

            if (scanPos != writePos) {
                var handle = handles.get(scanPos);
                I instance = instances.get(scanPos);

                handles.set(writePos, handle);
                instances.set(writePos, instance);

                long srcPtr = blocks[scanPos >>> LOG_2_PAGE_SIZE] + (long) (scanPos & PAGE_MASK) * instanceStride;
                long dstPtr = blocks[writePos >>> LOG_2_PAGE_SIZE] + (long) (writePos & PAGE_MASK) * instanceStride;
                MemoryUtil.memCopy(srcPtr, dstPtr, instanceStride);

                handle.index = writePos;
            }
        }

        deleted.clear();
        instances.subList(newSize, oldSize)
                 .clear();
        handles.subList(newSize, oldSize)
               .clear();
    }

    @Override
    public void delete() {
        for (InstancedDraw instancedDraw : draws) {
            instancedDraw.delete();
        }
        if (instanceTexels != null) {
            instanceTexels.close();
            instanceTexels = null;
        }
        clear();
        freeGlResources();
    }

    public void addDrawCall(InstancedDraw instancedDraw) {
        draws.add(instancedDraw);
    }
}
