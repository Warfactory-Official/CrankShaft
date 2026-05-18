package dev.engine_room.flywheel.backend.engine.instancing;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.backend.engine.BaseInstancer;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.SlabBuffer;
import dev.engine_room.flywheel.backend.gl.TextureBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

import static dev.engine_room.flywheel.backend.engine.EngineConstants.LOG_2_PAGE_SIZE;
import static dev.engine_room.flywheel.backend.engine.EngineConstants.PAGE_MASK;

public class InstancedInstancer<I extends Instance> extends BaseInstancer<I> {
    private final List<InstancedDraw> draws = new ArrayList<>();

    public InstancedInstancer(InstancerKey<I> key, Recreate<I> recreate) {
        super(key, recreate);
    }

    public List<InstancedDraw> draws() {
        return draws;
    }

    public void init() {
        // SlabBuffer is created lazily inside prepareUpload on first updateBuffer.
    }

    public void updateBuffer() {
        SlabBuffer buf = prepareUpload();
        if (buf == null || changed.isEmpty()) {
            return;
        }

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
            // clear() is Java-only (matches upstream's "without freeing resources" contract);
            // slabBuffer cleanup is deferred to delete() on the render thread via the
            // InstancedDrawManager.render removeIf(instanceCount==0) pass next frame.
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
        clear();
        freeGlResources();
    }

    public void addDrawCall(InstancedDraw instancedDraw) {
        draws.add(instancedDraw);
    }

    public void bind(TextureBuffer buffer) {
        SlabBuffer buf = slabBuffer;
        if (buf != null) {
            buffer.bind(buf.handle());
        }
    }
}
