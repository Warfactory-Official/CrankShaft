package dev.engine_room.flywheel.backend.vk.buffer;

import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.nio.LongBuffer;

public final class VkBuffer {
    private static final int VMA_MEMORY_USAGE_AUTO = 8;
    private static final int VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT = 1024;
    private static final int VMA_ALLOCATION_CREATE_MAPPED_BIT = 4;
    private static final int VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT = 1;
    private static final int VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT = 2;
    private static final int VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 4;

    private final long vma;
    private final int usage;
    private final boolean deviceLocal;
    private long buffer;
    private long allocation;
    private long mappedAddress;
    private long sizeBytes;
    private long deviceAddress;

    public VkBuffer(int usage, long sizeBytes) {
        this(usage, sizeBytes, false);
    }

    public VkBuffer(int usage, long sizeBytes, boolean deviceLocal) {
        this.vma = VkContext.vma();
        // BDA usage on every engine buffer (mirrors VulkanGpuBufferMixin's device-wide policy): descriptor-buffer set writes build descriptors from device addresses.
        this.usage = VkCaps.BUFFER_DEVICE_ADDRESS_NEGOTIATED ? usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT : usage;
        this.deviceLocal = deviceLocal;
        allocate(sizeBytes);
    }

    private static void check(int result, String what) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan error " + result + " during VkBuffer " + what);
        }
    }

    public long deviceAddress() {
        if (deviceAddress == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer);
                deviceAddress = VK12.vkGetBufferDeviceAddress(VkContext.vkDevice(), info);
            }
        }
        return deviceAddress;
    }

    public long vkBuffer() {
        return buffer;
    }

    public long mappedAddress() {
        return mappedAddress;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    /**
     * No-op: the allocation is host-coherent, so CPU writes need no explicit flush.
     */
    public void flush(long byteOffset, long byteSize) {
    }

    public boolean ensureCapacity(long minBytes) {
        if (minBytes <= sizeBytes) {
            return false;
        }
        long newSize = Math.max(minBytes, sizeBytes * 2);
        long oldBuffer = buffer;
        long oldAllocation = allocation;
        long oldMapped = mappedAddress;
        long oldSize = sizeBytes;
        allocate(newSize);
        if (!deviceLocal) {
            MemoryUtil.memCopy(oldMapped, mappedAddress, oldSize);
            Vma.vmaUnmapMemory(vma, oldAllocation);
        }
        VkContext.deferDestroy(() -> {
            Vma.vmaDestroyBuffer(vma, oldBuffer, oldAllocation);
            FlwMemoryTracker._freeGpuMemory(oldSize);
        });
        return true;
    }

    public void delete() {
        if (buffer == 0L) {
            return;
        }
        long oldVma = vma;
        long oldBuffer = buffer;
        long oldAllocation = allocation;
        long freed = sizeBytes;
        if (!deviceLocal) {
            Vma.vmaUnmapMemory(oldVma, oldAllocation);
        }
        VkContext.deferDestroy(() -> {
            Vma.vmaDestroyBuffer(oldVma, oldBuffer, oldAllocation);
            FlwMemoryTracker._freeGpuMemory(freed);
        });
        buffer = 0L;
        allocation = 0L;
        mappedAddress = 0L;
        sizeBytes = 0L;
    }

    private void allocate(long bytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                                                        .sType$Default()
                                                        .size(bytes)
                                                        .usage(usage)
                                                        .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO);
            if (deviceLocal) {
                // Pure-VRAM: no host access, no mapping; the GPU reads it at full bandwidth on all hardware.
                allocInfo.requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            } else {
                allocInfo.flags(
                                 VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT)
                         .requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                         .preferredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            }
            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAlloc = stack.callocPointer(1);
            check(Vma.vmaCreateBuffer(vma, info, allocInfo, pBuffer, pAlloc, null), "create");
            long newBuffer = pBuffer.get(0);
            long newAllocation = pAlloc.get(0);

            long newMapped = 0L;
            if (!deviceLocal) {
                PointerBuffer pMapped = stack.callocPointer(1);
                int mapResult = Vma.vmaMapMemory(vma, newAllocation, pMapped);
                if (mapResult != VK12.VK_SUCCESS) {
                    // The buffer was created but never handed off; free it before surfacing the map failure.
                    Vma.vmaDestroyBuffer(vma, newBuffer, newAllocation);
                    check(mapResult, "map");
                }
                newMapped = pMapped.get(0);
            }

            buffer = newBuffer;
            allocation = newAllocation;
            mappedAddress = newMapped;
            deviceAddress = 0L;
            sizeBytes = bytes;
            FlwMemoryTracker._allocGpuMemory(bytes);
        }
    }
}
