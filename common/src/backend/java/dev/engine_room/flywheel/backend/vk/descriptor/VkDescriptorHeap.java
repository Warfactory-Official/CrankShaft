package dev.engine_room.flywheel.backend.vk.descriptor;

import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * The shared {@code VK_EXT_descriptor_buffer} ring: a per-parity host-visible bump allocator that
 * {@link VkDescriptorWriter#flush} copies each draw's staged set into, bound per draw by offset instead of
 * re-embedding push descriptors. Parity flips with the encoder's submit index (two submits in flight, so slot
 * {@code N & 1} is idle when frame N records). Render-thread only.
 */
public final class VkDescriptorHeap {
    private static final long INITIAL_SIZE = 1L << 19;
    private static final int RING_USAGE = EXTDescriptorBuffer.VK_BUFFER_USAGE_RESOURCE_DESCRIPTOR_BUFFER_BIT_EXT
            | EXTDescriptorBuffer.VK_BUFFER_USAGE_SAMPLER_DESCRIPTOR_BUFFER_BIT_EXT
            | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

    private static final VkBuffer[] ring = new VkBuffer[2];
    private static long epoch = Long.MIN_VALUE;
    private static int parity;
    private static long bump;

    private static long foreignGeneration;
    private static long enginePushGeneration;
    private static long bufferBindGeneration;
    private static long boundGeneration = -1L;
    private static long boundAddress;
    private static long boundCmd;
    private static long bindsRecorded;
    private static long bindsElided;

    private static VkDescriptorBufferBindingInfoEXT.Buffer bindingInfo;
    private static IntBuffer bufferIndex;
    private static LongBuffer setOffset;
    private static VkBufferDeviceAddressInfo bdaInfo;
    private static VkDescriptorAddressInfoEXT addressInfo;
    private static VkDescriptorImageInfo imageInfo;
    private static VkDescriptorGetInfoEXT getInfo;

    private VkDescriptorHeap() {
    }

    public static long ensureFrame() {
        long submit = VkContext.encoder().currentSubmitIndex;
        if (submit != epoch) {
            if (ring[0] == null) {
                init();
            }
            epoch = submit;
            parity = (int) (submit & 1L);
            bump = 0L;
            boundGeneration = -1L;
        }
        return submit;
    }

    public static long write(long stagingPtr, long size) {
        VkBuffer buf = ring[parity];
        long align = VkCaps.DB_OFFSET_ALIGNMENT;
        long offset = (bump + align - 1) & -align;
        if (buf.ensureCapacity(offset + size)) {
            checkAlignment(buf);
        }
        MemoryUtil.memCopy(stagingPtr, buf.mappedAddress() + offset, size);
        bump = offset + size;
        return offset;
    }

    private static void checkAlignment(VkBuffer buf) {
        if ((buf.deviceAddress() & (VkCaps.DB_OFFSET_ALIGNMENT - 1)) != 0) {
            throw new IllegalStateException("Descriptor ring address 0x" + Long.toHexString(buf.deviceAddress())
                    + " violates descriptorBufferOffsetAlignment=" + VkCaps.DB_OFFSET_ALIGNMENT);
        }
    }

    public static void bind(VkCommandBuffer cmd, int bindPoint, long pipelineLayout, long offset) {
        long address = ring[parity].deviceAddress();
        long classicGen = foreignGeneration + enginePushGeneration;
        if (boundGeneration != classicGen || boundAddress != address || boundCmd != cmd.address()) {
            bindingInfo.get(0)
                       .address$(address)
                       .usage(RING_USAGE);
            EXTDescriptorBuffer.vkCmdBindDescriptorBuffersEXT(cmd, bindingInfo);
            boundGeneration = classicGen;
            boundAddress = address;
            boundCmd = cmd.address();
            bindsRecorded++;
            bufferBindGeneration++;
        } else {
            bindsElided++;
        }
        setOffset.put(0, offset);
        EXTDescriptorBuffer.vkCmdSetDescriptorBufferOffsetsEXT(cmd, bindPoint, pipelineLayout, 0, bufferIndex,
                setOffset);
    }

    public static void notifyForeignBind() {
        foreignGeneration++;
    }

    static void notifyEnginePush() {
        enginePushGeneration++;
    }

    static long foreignGeneration() {
        return foreignGeneration;
    }

    static long bufferBindGeneration() {
        return bufferBindGeneration;
    }

    public static long bindsRecorded() {
        return bindsRecorded;
    }

    public static long bindsElided() {
        return bindsElided;
    }

    static void writeBufferDescriptor(int type, long vkBuffer, long offset, long range, long dstPtr) {
        bdaInfo.buffer(vkBuffer);
        addressInfo.address$(VK12.vkGetBufferDeviceAddress(VkContext.vkDevice(), bdaInfo) + offset)
                   .range(range);
        getInfo.type(type);
        int size;
        if (type == VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
            getInfo.data().pUniformBuffer(addressInfo);
            size = VkCaps.DB_UNIFORM_BUFFER_SIZE;
        } else {
            getInfo.data().pStorageBuffer(addressInfo);
            size = VkCaps.DB_STORAGE_BUFFER_SIZE;
        }
        EXTDescriptorBuffer.nvkGetDescriptorEXT(VkContext.vkDevice(), getInfo.address(), size, dstPtr);
    }

    static void writeImageDescriptor(int type, long view, long sampler, long dstPtr) {
        imageInfo.imageView(view)
                 .sampler(sampler)
                 .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        getInfo.type(type);
        int size;
        if (type == VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
            getInfo.data().pCombinedImageSampler(imageInfo);
            size = VkCaps.DB_COMBINED_IMAGE_SAMPLER_SIZE;
        } else if (type == VK12.VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT) {
            getInfo.data().pInputAttachmentImage(imageInfo);
            size = VkCaps.DB_INPUT_ATTACHMENT_SIZE;
        } else {
            getInfo.data().pStorageImage(imageInfo);
            size = VkCaps.DB_STORAGE_IMAGE_SIZE;
        }
        EXTDescriptorBuffer.nvkGetDescriptorEXT(VkContext.vkDevice(), getInfo.address(), size, dstPtr);
    }

    private static void init() {
        ring[0] = new VkBuffer(RING_USAGE, INITIAL_SIZE);
        ring[1] = new VkBuffer(RING_USAGE, INITIAL_SIZE);
        checkAlignment(ring[0]);
        checkAlignment(ring[1]);
        bindingInfo = VkDescriptorBufferBindingInfoEXT.calloc(1);
        bindingInfo.get(0).sType$Default();
        bufferIndex = MemoryUtil.memCallocInt(1);
        setOffset = MemoryUtil.memCallocLong(1);
        bdaInfo = VkBufferDeviceAddressInfo.calloc().sType$Default();
        addressInfo = VkDescriptorAddressInfoEXT.calloc().sType$Default();
        imageInfo = VkDescriptorImageInfo.calloc();
        getInfo = VkDescriptorGetInfoEXT.calloc().sType$Default();
    }
}
