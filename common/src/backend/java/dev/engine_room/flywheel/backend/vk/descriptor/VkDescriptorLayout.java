package dev.engine_room.flywheel.backend.vk.descriptor;

import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.List;

public final class VkDescriptorLayout {
    public static final int TYPE_UNIFORM_BUFFER = VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    public static final int TYPE_STORAGE_BUFFER = VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    public static final int TYPE_COMBINED_IMAGE_SAMPLER = VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    public static final int TYPE_STORAGE_IMAGE = VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    public static final int TYPE_INPUT_ATTACHMENT = VK12.VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;

    public static final int STAGE_VERTEX = VK12.VK_SHADER_STAGE_VERTEX_BIT;
    public static final int STAGE_FRAGMENT = VK12.VK_SHADER_STAGE_FRAGMENT_BIT;
    public static final int STAGE_COMPUTE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final long[] EMPTY = new long[0];
    // NV driver bug (reproduced on 610.47 and 610.62): graphics-stage combined-image-sampler sets consumed
    // from a descriptor buffer MMU-fault a driver-internal descriptor-servicing kernel minutes into chunk
    // churn; those sets take the push path instead. Flip to false to retest against future drivers.
    private static final boolean DB_NO_GFX_SAMPLERS = true;
    private final long setLayout;
    private final long pipelineLayout;
    private final boolean descriptorBuffer;
    private final boolean bindlessTextures;
    private final boolean[] declared;
    private long setSize;
    private long stagingPtr;
    private long[] bindingOffsets = EMPTY;
    private long[] stagedEpoch = EMPTY;
    private long[] stagedA = EMPTY;
    private long[] stagedB = EMPTY;
    private long[] stagedC = EMPTY;

    public VkDescriptorLayout(List<Binding> bindings, int pushConstantSize, int pushConstantStages) {
        this(bindings, pushConstantSize, pushConstantStages, false);
    }

    public VkDescriptorLayout(List<Binding> bindings, int pushConstantSize, int pushConstantStages,
                              boolean bindlessTextures) {
        this.bindlessTextures = bindlessTextures && VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        boolean descriptorBuffer = VkCaps.DESCRIPTOR_BUFFER_NEGOTIATED
                && !this.bindlessTextures
                && !(DB_NO_GFX_SAMPLERS && hasGraphicsSamplerBindings(bindings));
        this.descriptorBuffer = descriptorBuffer;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer vkBindings = VkDescriptorSetLayoutBinding.calloc(bindings.size(),
                    stack);
            for (int i = 0; i < bindings.size(); i++) {
                Binding b = bindings.get(i);
                vkBindings.get(i)
                          .binding(b.binding())
                          .descriptorType(b.type())
                          .descriptorCount(1)
                          .stageFlags(b.stageFlags());
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                                                                                        .sType$Default()
                                                                                        .flags(descriptorBuffer
                                                                                                ? EXTDescriptorBuffer.VK_DESCRIPTOR_SET_LAYOUT_CREATE_DESCRIPTOR_BUFFER_BIT_EXT
                                                                                                : KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                                                                                        .pBindings(vkBindings);
            LongBuffer pSetLayout = stack.callocLong(1);
            check(VK12.vkCreateDescriptorSetLayout(VkContext.vkDevice(), layoutInfo, null, pSetLayout),
                    "descriptor set layout");
            this.setLayout = pSetLayout.get(0);

            VkPipelineLayoutCreateInfo pipelineInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                                                                                .sType$Default()
                                                                                .pSetLayouts(this.bindlessTextures
                                                                                        ? stack.longs(this.setLayout,
                                                                                        VkBindlessTable.setLayoutHandle())
                                                                                        : stack.longs(this.setLayout));
            if (pushConstantSize > 0) {
                VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack)
                                                                        .stageFlags(pushConstantStages)
                                                                        .offset(0)
                                                                        .size(pushConstantSize);
                pipelineInfo.pPushConstantRanges(pcRange);
            }
            LongBuffer pPipelineLayout = stack.callocLong(1);
            check(VK12.vkCreatePipelineLayout(VkContext.vkDevice(), pipelineInfo, null, pPipelineLayout),
                    "pipeline layout");
            this.pipelineLayout = pPipelineLayout.get(0);

            int maxBinding = 0;
            for (Binding b : bindings) {
                maxBinding = Math.max(maxBinding, b.binding());
            }
            this.declared = new boolean[maxBinding + 1];
            for (Binding b : bindings) {
                declared[b.binding()] = true;
            }
            this.stagedEpoch = new long[maxBinding + 1];
            Arrays.fill(stagedEpoch, Long.MIN_VALUE);
            this.stagedA = new long[maxBinding + 1];
            this.stagedB = new long[maxBinding + 1];
            this.stagedC = new long[maxBinding + 1];

            if (descriptorBuffer) {
                LongBuffer pValue = stack.callocLong(1);
                EXTDescriptorBuffer.vkGetDescriptorSetLayoutSizeEXT(VkContext.vkDevice(), setLayout, pValue);
                this.setSize = pValue.get(0);

                this.bindingOffsets = new long[maxBinding + 1];
                Arrays.fill(bindingOffsets, -1L);
                for (Binding b : bindings) {
                    EXTDescriptorBuffer.vkGetDescriptorSetLayoutBindingOffsetEXT(VkContext.vkDevice(), setLayout,
                            b.binding(), pValue);
                    bindingOffsets[b.binding()] = pValue.get(0);
                }
                this.stagingPtr = MemoryUtil.nmemCalloc(1, setSize);
            }
        }
    }

    private static boolean hasGraphicsSamplerBindings(List<Binding> bindings) {
        for (Binding b : bindings) {
            if (b.type() == TYPE_COMBINED_IMAGE_SAMPLER && (b.stageFlags() & ~STAGE_COMPUTE) != 0) {
                return true;
            }
        }
        return false;
    }

    private static void check(int result, String what) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan error " + result + " creating " + what);
        }
    }

    public boolean usesDescriptorBuffer() {
        return descriptorBuffer;
    }

    public boolean bindlessTextures() {
        return bindlessTextures;
    }

    public long setLayout() {
        return setLayout;
    }

    public long pipelineLayout() {
        return pipelineLayout;
    }

    long setSize() {
        return setSize;
    }

    long stagingPtr() {
        return stagingPtr;
    }

    // Frame epochs invalidate every staged blob at once: handles can only be recycled across frames, so a same-params match within an epoch is the same resource.

    void stageBuffer(long epoch, int binding, int type, long vkBuffer, long offset, long range) {
        if (stagedEpoch[binding] == epoch && stagedA[binding] == vkBuffer && stagedB[binding] == offset
                && stagedC[binding] == range) {
            return;
        }
        VkDescriptorHeap.writeBufferDescriptor(type, vkBuffer, offset, range, stagingPtr + offsetOrThrow(binding));
        stagedEpoch[binding] = epoch;
        stagedA[binding] = vkBuffer;
        stagedB[binding] = offset;
        stagedC[binding] = range;
    }

    boolean pushStale(long token, int binding, long a, long b, long c) {
        if (binding >= declared.length || !declared[binding]) {
            throw new IllegalStateException("Pushed binding " + binding + " is not declared by this layout");
        }
        if (stagedEpoch[binding] == token && stagedA[binding] == a && stagedB[binding] == b && stagedC[binding] == c) {
            return false;
        }
        stagedEpoch[binding] = token;
        stagedA[binding] = a;
        stagedB[binding] = b;
        stagedC[binding] = c;
        return true;
    }

    void stageImage(long epoch, int binding, int type, long view, long sampler) {
        if (stagedEpoch[binding] == epoch && stagedA[binding] == view && stagedB[binding] == sampler) {
            return;
        }
        VkDescriptorHeap.writeImageDescriptor(type, view, sampler, stagingPtr + offsetOrThrow(binding));
        stagedEpoch[binding] = epoch;
        stagedA[binding] = view;
        stagedB[binding] = sampler;
        stagedC[binding] = 0L;
    }

    private long offsetOrThrow(int binding) {
        long offset = bindingOffsets[binding];
        if (offset < 0) {
            throw new IllegalStateException(
                    "Staged descriptor binding " + binding + " is not declared by this pipeline's layout");
        }
        return offset;
    }

    public void delete() {
        if (stagingPtr != 0L) {
            MemoryUtil.nmemFree(stagingPtr);
            stagingPtr = 0L;
        }
        long pl = pipelineLayout;
        long sl = setLayout;
        VkContext.deferDestroy(() -> {
            VK12.vkDestroyPipelineLayout(VkContext.vkDevice(), pl, null);
            VK12.vkDestroyDescriptorSetLayout(VkContext.vkDevice(), sl, null);
        });
    }

    public record Binding(int binding, int type, int stageFlags) {
    }
}
