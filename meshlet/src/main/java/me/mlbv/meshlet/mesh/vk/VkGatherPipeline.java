// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock

package me.mlbv.meshlet.mesh.vk;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.engine_room.flywheel.backend.vk.VkContext;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

public final class VkGatherPipeline {
    static final int PUSH_CONSTANT_BYTES = 20;

    private final VkDevice device;
    private final long pipeline;
    private final long pipelineLayout;
    private final long module;

    public VkGatherPipeline(ByteBuffer spv) {
        this.device = VkContext.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spv);
            LongBuffer pModule = stack.callocLong(1);
            check(VK12.vkCreateShaderModule(device, moduleInfo, null, pModule), "create gather shader module");
            long module = pModule.get(0);

            long pipelineLayout;
            try {
                VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                        .offset(0)
                        .size(PUSH_CONSTANT_BYTES)
                        .stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pPushConstantRanges(pushRange);
                LongBuffer pLayout = stack.callocLong(1);
                check(VK12.vkCreatePipelineLayout(device, layoutInfo, null, pLayout), "create gather pipeline layout");
                pipelineLayout = pLayout.get(0);
            } catch (Throwable t) {
                VK12.vkDestroyShaderModule(device, module, null);
                throw t;
            }

            long pipeline;
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default()
                        .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(module)
                        .pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer createInfo = VkComputePipelineCreateInfo.calloc(1, stack)
                        .sType$Default()
                        .stage(stage)
                        .layout(pipelineLayout);
                LongBuffer pPipeline = stack.callocLong(1);
                check(VK12.vkCreateComputePipelines(device, 0L, createInfo, null, pPipeline), "create gather pipeline");
                pipeline = pPipeline.get(0);
            } catch (Throwable t) {
                VK12.vkDestroyPipelineLayout(device, pipelineLayout, null);
                VK12.vkDestroyShaderModule(device, module, null);
                throw t;
            }

            this.module = module;
            this.pipelineLayout = pipelineLayout;
            this.pipeline = pipeline;
        }
    }

    public long handle() {
        return pipeline;
    }

    public long layout() {
        return pipelineLayout;
    }

    public void destroy() {
        VkContext.deferDestroy(() -> {
            VK12.vkDestroyPipeline(device, pipeline, null);
            VK12.vkDestroyPipelineLayout(device, pipelineLayout, null);
            VK12.vkDestroyShaderModule(device, module, null);
        });
    }

    private static void check(int result, String what) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan error " + result + " during " + what);
        }
    }
}
