package dev.engine_room.flywheel.backend.vk.descriptor;

import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.engine_room.flywheel.backend.engine.BindlessSlots;
import dev.engine_room.flywheel.backend.engine.MaterialSamplers;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import net.minecraft.client.renderer.texture.TextureManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

public final class VkBindlessTable {
    public static final int MIN_CAPACITY = 4096;
    public static final int MAX_CAPACITY = 65536;

    // Reserved frame-resource slots: the indices are FIXED -- the _FLW_BINDLESS shader blocks hardcode them -- keep both sides in sync.
    public static final int SLOT_OVERLAY = 1;
    public static final int SLOT_LIGHTMAP = 2;
    public static final int SLOT_BLUE_NOISE = 3;
    public static final int SLOT_DEPTH_RANGE = 4;
    public static final int SLOT_COEFFICIENTS_BASE = 5;
    private static final long[] set = new long[2];
    private static long[][] writtenView;
    private static long[][] writtenSampler;
    private static long setLayout;
    private static long pool;
    private static int parity;

    private VkBindlessTable() {
    }

    /**
     * Point a reserved frame-resource slot at {@code view} + {@code sampler}. MUST run outside any render pass.
     */
    public static void setReserved(int slot, long view, long sampler) {
        ensureInit();
        parity = currentParity();
        if (writtenView[parity][slot] == view && writtenSampler[parity][slot] == sampler) {
            return;
        }
        writeSlot(parity, slot, view, sampler);
    }

    public static void refresh(TextureManager textureManager) {
        ensureInit();
        parity = currentParity();
        int count = BindlessSlots.count();
        if (count + BindlessSlots.FIRST_MATERIAL_SLOT > VkCaps.BINDLESS_TABLE_CAPACITY) {
            throw new IllegalStateException(
                    "Bindless texture table exhausted (" + VkCaps.BINDLESS_TABLE_CAPACITY + " slots)");
        }
        for (int i = 0; i < count; i++) {
            BindlessSlots.Key key = BindlessSlots.key(i);
            long view = ((VulkanGpuTextureView) textureManager.getTexture(key.texture())
                                                              .getTextureView()).vkImageView();
            long sampler = ((VulkanGpuSampler) MaterialSamplers.get(key.texture(), key.blur(),
                    key.mipmap())).vkSampler();
            int s = i + BindlessSlots.FIRST_MATERIAL_SLOT;
            if (writtenView[parity][s] == view && writtenSampler[parity][s] == sampler) {
                continue;
            }
            writeSlot(parity, s, view, sampler);
        }
    }

    private static int currentParity() {
        return (int) (VkContext.encoder().currentSubmitIndex & 1L);
    }

    private static void writeSlot(int parity, int slot, long view, long sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
                                                                     .imageView(view)
                                                                     .sampler(sampler)
                                                                     .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                                                                    .sType$Default()
                                                                    .dstSet(set[parity])
                                                                    .dstBinding(0)
                                                                    .dstArrayElement(slot)
                                                                    .descriptorCount(1)
                                                                    .descriptorType(
                                                                            VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                                                    .pImageInfo(info);
            VK12.vkUpdateDescriptorSets(VkContext.vkDevice(), write, null);
        }
        writtenView[parity][slot] = view;
        writtenSampler[parity][slot] = sampler;
    }

    public static void bind(VkCommandBuffer cmd, long pipelineLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindDescriptorSets(cmd, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 1,
                    stack.longs(set[parity]), null);
        }
    }

    public static long setLayoutHandle() {
        ensureInit();
        return setLayout;
    }

    private static void ensureInit() {
        if (setLayout != 0L) {
            return;
        }
        int capacity = VkCaps.BINDLESS_TABLE_CAPACITY;
        writtenView = new long[2][capacity];
        writtenSampler = new long[2][capacity];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                                                                                      .binding(0)
                                                                                      .descriptorType(
                                                                                              VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                                                                      .descriptorCount(capacity)
                                                                                      .stageFlags(
                                                                                              VK12.VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutBindingFlagsCreateInfo bindingFlags = VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(
                                                                                                                          stack)
                                                                                                                  .sType$Default()
                                                                                                                  .pBindingFlags(
                                                                                                                          stack.ints(
                                                                                                                                  VK12.VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT
                                                                                                                                          | VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT
                                                                                                                                          | VK12.VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT));
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                                                                        .sType$Default()
                                                                                        .pNext(bindingFlags.address())
                                                                                        .flags(VK12.VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT)
                                                                                        .pBindings(binding);
            var pLayout = stack.callocLong(1);
            check(VK12.vkCreateDescriptorSetLayout(VkContext.vkDevice(), layoutInfo, null, pLayout), "set layout");
            setLayout = pLayout.get(0);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                                                                       .type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                                                       .descriptorCount(capacity * 2);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                                                                            .sType$Default()
                                                                            .flags(VK12.VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT)
                                                                            .maxSets(2)
                                                                            .pPoolSizes(poolSize);
            var pPool = stack.callocLong(1);
            check(VK12.vkCreateDescriptorPool(VkContext.vkDevice(), poolInfo, null, pPool), "pool");
            pool = pPool.get(0);

            VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack)
                                                                           .sType$Default()
                                                                           .descriptorPool(pool)
                                                                           .pSetLayouts(
                                                                                   stack.longs(setLayout, setLayout));
            var pSets = stack.callocLong(2);
            check(VK12.vkAllocateDescriptorSets(VkContext.vkDevice(), alloc, pSets), "set");
            set[0] = pSets.get(0);
            set[1] = pSets.get(1);
        }
    }

    private static void check(int result, String what) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan error " + result + " creating bindless texture " + what);
        }
    }
}
