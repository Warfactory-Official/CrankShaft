package dev.engine_room.flywheel.backend.vk.descriptor;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.Arrays;

public final class VkDescriptorWriter {
    private static final VkDescriptorLayout[] lastLayout = new VkDescriptorLayout[2];
    private static long lastEpoch = Long.MIN_VALUE;
    private static long lastForeignGen = -1L;
    private static long lastBufferBindGen = -1L;
    private static long pushToken;
    private int[] bufBinding = new int[16];
    private int[] bufType = new int[16];
    private long[] bufHandle = new long[16];
    private long[] bufOffset = new long[16];
    private long[] bufRange = new long[16];
    private boolean[] bufEmit = new boolean[16];
    private int bufCount;
    private int[] imgBinding = new int[16];
    private int[] imgType = new int[16];
    private long[] imgView = new long[16];
    private long[] imgSampler = new long[16];
    private boolean[] imgEmit = new boolean[16];
    private int imgCount;

    private static long pushToken(int bindPoint, VkDescriptorLayout layout) {
        long epoch = VkContext.encoder().currentSubmitIndex;
        long foreignGen = VkDescriptorHeap.foreignGeneration();
        long bufferBindGen = VkDescriptorHeap.bufferBindGeneration();
        if (epoch != lastEpoch || foreignGen != lastForeignGen || bufferBindGen != lastBufferBindGen) {
            lastEpoch = epoch;
            lastForeignGen = foreignGen;
            lastBufferBindGen = bufferBindGen;
            lastLayout[0] = null;
            lastLayout[1] = null;
            pushToken++;
        }
        int bp = bindPoint == VK12.VK_PIPELINE_BIND_POINT_COMPUTE ? 1 : 0;
        if (lastLayout[bp] != layout) {
            lastLayout[bp] = layout;
            pushToken++;
        }
        return pushToken;
    }

    public VkDescriptorWriter storage(int binding, long vkBuffer, long offset, long range) {
        addBuffer(binding, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, vkBuffer, offset, range);
        return this;
    }

    public VkDescriptorWriter storage(int binding, VkBuffer buffer) {
        return storage(binding, buffer.vkBuffer(), 0L, buffer.sizeBytes());
    }

    public VkDescriptorWriter uniform(int binding, long vkBuffer, long offset, long range) {
        addBuffer(binding, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, vkBuffer, offset, range);
        return this;
    }

    public VkDescriptorWriter uniform(int binding, GpuBufferSlice slice) {
        return uniform(binding, ((VulkanGpuBuffer) slice.buffer()).vkBuffer(), slice.offset(), slice.length());
    }

    public VkDescriptorWriter uniform(int binding, GpuBuffer buffer) {
        return uniform(binding, ((VulkanGpuBuffer) buffer).vkBuffer(), 0L, buffer.size());
    }

    public VkDescriptorWriter uniform(int binding, VkBuffer buffer) {
        return uniform(binding, buffer.vkBuffer(), 0L, buffer.sizeBytes());
    }

    public VkDescriptorWriter sampler(int binding, long imageView, long sampler) {
        addImage(binding, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, imageView, sampler);
        return this;
    }

    public VkDescriptorWriter image(int binding, long imageView) {
        addImage(binding, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, imageView, 0L);
        return this;
    }

    public VkDescriptorWriter inputAttachment(int binding, long imageView) {
        addImage(binding, VK12.VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT, imageView, 0L);
        return this;
    }

    private void addBuffer(int binding, int type, long handle, long offset, long range) {
        int i = bufCount;
        if (i == bufBinding.length) {
            int cap = i * 2;
            bufBinding = Arrays.copyOf(bufBinding, cap);
            bufType = Arrays.copyOf(bufType, cap);
            bufHandle = Arrays.copyOf(bufHandle, cap);
            bufOffset = Arrays.copyOf(bufOffset, cap);
            bufRange = Arrays.copyOf(bufRange, cap);
            bufEmit = new boolean[cap];
        }
        bufBinding[i] = binding;
        bufType[i] = type;
        bufHandle[i] = handle;
        bufOffset[i] = offset;
        bufRange[i] = range;
        bufCount = i + 1;
    }

    private void addImage(int binding, int type, long view, long sampler) {
        int i = imgCount;
        if (i == imgBinding.length) {
            int cap = i * 2;
            imgBinding = Arrays.copyOf(imgBinding, cap);
            imgType = Arrays.copyOf(imgType, cap);
            imgView = Arrays.copyOf(imgView, cap);
            imgSampler = Arrays.copyOf(imgSampler, cap);
            imgEmit = new boolean[cap];
        }
        imgBinding[i] = binding;
        imgType[i] = type;
        imgView[i] = view;
        imgSampler[i] = sampler;
        imgCount = i + 1;
    }

    public void flush(VkCommandBuffer cmd, int bindPoint, VkDescriptorLayout layout) {
        if (!layout.usesDescriptorBuffer()) {
            push(cmd, bindPoint, layout);
            return;
        }
        if (bufCount + imgCount == 0) {
            return;
        }
        long epoch = VkDescriptorHeap.ensureFrame();
        for (int b = 0; b < bufCount; b++) {
            layout.stageBuffer(epoch, bufBinding[b], bufType[b], bufHandle[b], bufOffset[b], bufRange[b]);
        }
        for (int im = 0; im < imgCount; im++) {
            layout.stageImage(epoch, imgBinding[im], imgType[im], imgView[im], imgSampler[im]);
        }
        long offset = VkDescriptorHeap.write(layout.stagingPtr(), layout.setSize());
        VkDescriptorHeap.bind(cmd, bindPoint, layout.pipelineLayout(), offset);
        bufCount = 0;
        imgCount = 0;
    }

    private void push(VkCommandBuffer cmd, int bindPoint, VkDescriptorLayout layout) {
        long token = pushToken(bindPoint, layout);
        int count = 0;
        for (int b = 0; b < bufCount; b++) {
            bufEmit[b] = layout.pushStale(token, bufBinding[b], bufHandle[b], bufOffset[b], bufRange[b]);
            if (bufEmit[b]) {
                count++;
            }
        }
        for (int im = 0; im < imgCount; im++) {
            imgEmit[im] = layout.pushStale(token, imgBinding[im], imgView[im], imgSampler[im], 0L);
            if (imgEmit[im]) {
                count++;
            }
        }
        if (count > 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(count, stack);
                int i = 0;
                for (int b = 0; b < bufCount; b++) {
                    if (!bufEmit[b]) {
                        continue;
                    }
                    VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                                                                               .buffer(bufHandle[b])
                                                                               .offset(bufOffset[b])
                                                                               .range(bufRange[b]);
                    writes.get(i++)
                          .sType$Default()
                          .dstBinding(bufBinding[b])
                          .dstArrayElement(0)
                          .descriptorCount(1)
                          .descriptorType(bufType[b])
                          .pBufferInfo(info);
                }
                for (int im = 0; im < imgCount; im++) {
                    if (!imgEmit[im]) {
                        continue;
                    }
                    VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
                                                                             .imageView(imgView[im])
                                                                             .sampler(imgSampler[im])
                                                                             .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                    writes.get(i++)
                          .sType$Default()
                          .dstBinding(imgBinding[im])
                          .dstArrayElement(0)
                          .descriptorCount(1)
                          .descriptorType(imgType[im])
                          .pImageInfo(info);
                }
                KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cmd, bindPoint, layout.pipelineLayout(), 0, writes);
            }
            VkDescriptorHeap.notifyEnginePush();
        }
        bufCount = 0;
        imgCount = 0;
    }
}
