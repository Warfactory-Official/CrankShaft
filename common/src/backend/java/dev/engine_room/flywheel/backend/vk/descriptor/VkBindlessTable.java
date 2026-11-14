package dev.engine_room.flywheel.backend.vk.descriptor;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
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
    private static VulkanGpuTextureView[][] writtenOwnerView;
    private static VulkanGpuSampler[][] writtenOwnerSampler;
    // Volatile: read by pipeline-layout creation off the render thread (existingSetLayout).
    private static volatile long setLayout;
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
            var viewObject = (VulkanGpuTextureView) textureManager.getTexture(key.texture()).getTextureView();
            var samplerObject = (VulkanGpuSampler) MaterialSamplers.get(key.texture(), key.blur(),
                    key.mipmap(), key.useTextureSampler());
            long view = viewObject.vkImageView();
            long sampler = samplerObject.vkSampler();
            if (key.useTextureSampler() && writtenOwnerView == null) {
                writtenOwnerView = new VulkanGpuTextureView[2][VkCaps.BINDLESS_TABLE_CAPACITY];
                writtenOwnerSampler = new VulkanGpuSampler[2][VkCaps.BINDLESS_TABLE_CAPACITY];
            }
            int s = i + BindlessSlots.FIRST_MATERIAL_SLOT;
            if (writtenView[parity][s] == view && writtenSampler[parity][s] == sampler
                    && (!key.useTextureSampler() || writtenOwnerView[parity][s] == viewObject
                    && writtenOwnerSampler[parity][s] == samplerObject)) {
                continue;
            }
            writeSlot(parity, s, view, sampler);
            if (key.useTextureSampler()) {
                writtenOwnerView[parity][s] = viewObject;
                writtenOwnerSampler[parity][s] = samplerObject;
            }
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
                                                                     .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                                                                    .sType$Default()
                                                                    .dstSet(set[parity])
                                                                    .dstBinding(0)
                                                                    .dstArrayElement(slot)
                                                                    .descriptorCount(1)
                                                                    .descriptorType(
                                                                            VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                                                    .pImageInfo(info);
            VK10.vkUpdateDescriptorSets(VkContext.vkDevice(), write, null);
        }
        writtenView[parity][slot] = view;
        writtenSampler[parity][slot] = sampler;
    }

    public static void bind(VkCommandBuffer cmd, long pipelineLayout) {
        bind(cmd, pipelineLayout, 1);
    }

    public static void bind(VkCommandBuffer cmd, long pipelineLayout, int setIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, setIndex,
                    stack.longs(set[parity]), null);
        }
    }

    /**
     * 0 until the render thread initialized the table; never initializes it.
     */
    public static long existingSetLayout() {
        return setLayout;
    }

    public static long setLayoutHandle() {
        ensureInit();
        return setLayout;
    }

    /**
     * Render thread, device idle. The next use re-creates the table.
     */
    public static void destroy() {
        if (setLayout == 0L) {
            return;
        }
        VK10.vkDestroyDescriptorPool(VkContext.vkDevice(), pool, null);
        VK10.vkDestroyDescriptorSetLayout(VkContext.vkDevice(), setLayout, null);
        pool = 0L;
        setLayout = 0L;
        set[0] = 0L;
        set[1] = 0L;
        writtenView = null;
        writtenSampler = null;
        writtenOwnerView = null;
        writtenOwnerSampler = null;
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
                                                                                              VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                                                                      .descriptorCount(capacity)
                                                                                      .stageFlags(
                                                                                              VK10.VK_SHADER_STAGE_VERTEX_BIT
                                                                                                      | VK10.VK_SHADER_STAGE_FRAGMENT_BIT);
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
            check(VK10.vkCreateDescriptorSetLayout(VkContext.vkDevice(), layoutInfo, null, pLayout), "set layout");
            setLayout = pLayout.get(0);

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                                                                       .type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                                                       .descriptorCount(capacity * 2);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                                                                            .sType$Default()
                                                                            .flags(VK12.VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT)
                                                                            .maxSets(2)
                                                                            .pPoolSizes(poolSize);
            var pPool = stack.callocLong(1);
            check(VK10.vkCreateDescriptorPool(VkContext.vkDevice(), poolInfo, null, pPool), "pool");
            pool = pPool.get(0);

            VkDescriptorSetAllocateInfo alloc = VkDescriptorSetAllocateInfo.calloc(stack)
                                                                           .sType$Default()
                                                                           .descriptorPool(pool)
                                                                           .pSetLayouts(
                                                                                   stack.longs(setLayout, setLayout));
            var pSets = stack.callocLong(2);
            check(VK10.vkAllocateDescriptorSets(VkContext.vkDevice(), alloc, pSets), "set");
            set[0] = pSets.get(0);
            set[1] = pSets.get(1);
        }
    }

    private static void check(int result, String what) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan error " + result + " creating bindless texture " + what);
        }
    }
}
