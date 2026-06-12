package dev.engine_room.flywheel.backend.engine.indirect;

import dev.engine_room.flywheel.backend.vk.VkCmd;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.BitSet;

public final class VkObjectStorage extends AbstractObjectStorage {
    private static final long INITIAL_OBJECT_BYTES = 1 << 16;
    private static final int INITIAL_SLOTS = 64;

    private static final int OBJECT_USAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    private static final int DESCRIPTOR_USAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

    private final VkBuffer[] objectBuffers = {
            new VkBuffer(OBJECT_USAGE, INITIAL_OBJECT_BYTES, true),
            new VkBuffer(OBJECT_USAGE, INITIAL_OBJECT_BYTES, true),
    };
    private final VkBuffer[] descriptorBuffers = {
            new VkBuffer(DESCRIPTOR_USAGE, (long) INITIAL_SLOTS * DESCRIPTOR_SIZE_BYTES),
            new VkBuffer(DESCRIPTOR_USAGE, (long) INITIAL_SLOTS * DESCRIPTOR_SIZE_BYTES),
    };
    private final BitSet[] pendingSlots = {new BitSet(), new BitSet()};
    private long shadowPtr = FlwMemoryTracker.calloc(INITIAL_SLOTS, DESCRIPTOR_SIZE_BYTES);
    private long shadowBytes = (long) INITIAL_SLOTS * DESCRIPTOR_SIZE_BYTES;
    private int parity;

    public VkObjectStorage() {
        FlwMemoryTracker._allocCpuMemory(shadowBytes);
    }

    public VkBuffer objectBuffer() {
        return objectBuffers[parity];
    }

    public VkBuffer frameDescriptorBuffer() {
        return descriptorBuffers[parity];
    }

    public void beginFrame(int parity) {
        this.parity = parity;
        growObject(parity, bytesUsed());
        descriptorBuffers[parity].ensureCapacity((long) pageSlotCount() * DESCRIPTOR_SIZE_BYTES);
    }

    private void growObject(int parity, long minBytes) {
        VkBuffer buffer = objectBuffers[parity];
        long oldBuffer = buffer.vkBuffer();
        long oldBytes = buffer.sizeBytes();
        if (!buffer.ensureCapacity(minBytes)) {
            return;
        }
        VkCommandBuffer cmd = VkContext.beginCommands();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).set(0L, 0L, oldBytes);
            VK12.vkCmdCopyBuffer(cmd, oldBuffer, buffer.vkBuffer(), region);
        }
        VkCmd.memoryBarrier(cmd, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK12.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT | VK12.VK_ACCESS_TRANSFER_READ_BIT | VK12.VK_ACCESS_SHADER_READ_BIT);
        VkContext.submitCommands(cmd);
    }

    public void flushDescriptors() {
        BitSet pending = pendingSlots[parity];
        if (pending.isEmpty()) {
            return;
        }
        long dst = descriptorBuffers[parity].mappedAddress();
        for (int slot = pending.nextSetBit(0); slot >= 0; slot = pending.nextSetBit(slot + 1)) {
            long off = (long) slot * DESCRIPTOR_SIZE_BYTES;
            MemoryUtil.memCopy(shadowPtr + off, dst + off, DESCRIPTOR_SIZE_BYTES);
        }
        pending.clear();
    }

    @Override
    protected void ensureCapacities(long objectBytes, int slotCount) {
        growObject(parity, objectBytes);
        long descriptorBytes = (long) slotCount * DESCRIPTOR_SIZE_BYTES;
        descriptorBuffers[parity].ensureCapacity(descriptorBytes);
        if (descriptorBytes > shadowBytes) {
            long newBytes = Math.max(shadowBytes * 2, descriptorBytes);
            shadowPtr = FlwMemoryTracker.realloc(shadowPtr, newBytes);
            FlwMemoryTracker._allocCpuMemory(newBytes - shadowBytes);
            shadowBytes = newBytes;
        }
    }

    @Override
    protected void writeDescriptor(int slot, int modelIndex, int validBits, int baseUint, int typeInfo) {
        long ptr = shadowPtr + (long) slot * DESCRIPTOR_SIZE_BYTES;
        MemoryUtil.memPutInt(ptr, modelIndex);
        MemoryUtil.memPutInt(ptr + 4, validBits);
        MemoryUtil.memPutInt(ptr + 8, baseUint);
        MemoryUtil.memPutInt(ptr + 12, typeInfo);
        pendingSlots[0].set(slot);
        pendingSlots[1].set(slot);
    }

    @Override
    public void delete() {
        objectBuffers[0].delete();
        objectBuffers[1].delete();
        descriptorBuffers[0].delete();
        descriptorBuffers[1].delete();
        FlwMemoryTracker.free(shadowPtr);
        FlwMemoryTracker._freeCpuMemory(shadowBytes);
        shadowPtr = 0L;
    }
}
