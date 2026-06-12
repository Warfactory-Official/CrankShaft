package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import org.lwjgl.system.MemoryUtil;

import java.util.BitSet;

public class ObjectStorage extends AbstractObjectStorage {
    private static final long INITIAL_OBJECT_BYTES = 1 << 16;
    private static final int INITIAL_SLOTS = 64;
    public final ResizableStorageBuffer objectBuffer;
    public final ResizableStorageBuffer frameDescriptorBuffer;
    private final BitSet changedFrames = new BitSet();
    /**
     * The CPU side memory block containing the page descriptors.
     */
    private MemoryBlock frameDescriptors;

    public ObjectStorage() {
        this.objectBuffer = new ResizableStorageBuffer();
        this.frameDescriptorBuffer = new ResizableStorageBuffer();

        objectBuffer.ensureCapacity(INITIAL_OBJECT_BYTES);
        frameDescriptorBuffer.ensureCapacity((long) INITIAL_SLOTS * DESCRIPTOR_SIZE_BYTES);
        frameDescriptors = MemoryBlock.calloc(INITIAL_SLOTS * DESCRIPTOR_SIZE_BYTES, 1);
    }

    @Override
    protected void ensureCapacities(long objectBytes, int slotCount) {
        if (objectBytes > objectBuffer.capacity()) {
            objectBuffer.ensureCapacity(Math.max(objectBuffer.capacity() * 2, objectBytes));
        }
        long descriptorBytes = (long) slotCount * DESCRIPTOR_SIZE_BYTES;
        if (descriptorBytes > frameDescriptorBuffer.capacity()) {
            long newSize = Math.max(frameDescriptorBuffer.capacity() * 2, descriptorBytes);
            frameDescriptorBuffer.ensureCapacity(newSize);
            frameDescriptors = frameDescriptors.realloc(newSize);
        }
    }

    @Override
    protected void writeDescriptor(int slot, int modelIndex, int validBits, int baseUint, int typeInfo) {
        long ptr = frameDescriptors.ptr() + (long) slot * DESCRIPTOR_SIZE_BYTES;
        MemoryUtil.memPutInt(ptr, modelIndex);
        MemoryUtil.memPutInt(ptr + 4, validBits);
        MemoryUtil.memPutInt(ptr + 8, baseUint);
        MemoryUtil.memPutInt(ptr + 12, typeInfo);

        changedFrames.set(slot);
    }

    public void uploadDescriptors(StagingBuffer stagingBuffer) {
        if (changedFrames.isEmpty()) {
            return;
        }

        var ptr = frameDescriptors.ptr();
        for (int i = changedFrames.nextSetBit(0); i >= 0 && i < pageSlotCount(); i = changedFrames.nextSetBit(i + 1)) {
            var offset = (long) i * DESCRIPTOR_SIZE_BYTES;
            stagingBuffer.enqueueCopy(ptr + offset, DESCRIPTOR_SIZE_BYTES, frameDescriptorBuffer.handle(), offset);
        }

        changedFrames.clear();
    }

    @Override
    public void delete() {
        objectBuffer.delete();
        frameDescriptorBuffer.delete();
        frameDescriptors.free();
    }
}
