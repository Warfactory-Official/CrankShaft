package dev.engine_room.flywheel.backend.vk.shader;

import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDescriptorBuffer;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;

import java.nio.LongBuffer;

public final class VkComputePipeline {
    private final VkDescriptorLayout layout;
    private final long module;
    private final long pipeline;

    public VkComputePipeline(VkDescriptorLayout layout, long shaderModule) {
        this.layout = layout;
        this.module = shaderModule;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                                                                                   .sType$Default()
                                                                                   .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                                                                                   .module(shaderModule)
                                                                                   .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack)
                                                                                 .sType$Default()
                                                                                 .flags(layout.usesDescriptorBuffer()
                                                                                         ? EXTDescriptorBuffer.VK_PIPELINE_CREATE_DESCRIPTOR_BUFFER_BIT_EXT : 0)
                                                                                 .stage(stage)
                                                                                 .layout(layout.pipelineLayout());
            LongBuffer pPipeline = stack.callocLong(1);
            int result = VK12.vkCreateComputePipelines(VkContext.vkDevice(), 0L, info, null, pPipeline);
            if (result != VK12.VK_SUCCESS) {
                throw new IllegalStateException("Vulkan error " + result + " creating compute pipeline");
            }
            this.pipeline = pPipeline.get(0);
        }
    }

    public long handle() {
        return pipeline;
    }

    public VkDescriptorLayout layout() {
        return layout;
    }

    public void delete() {
        long p = pipeline;
        long m = module;
        VkContext.deferDestroy(() -> {
            VK12.vkDestroyPipeline(VkContext.vkDevice(), p, null);
            VK12.vkDestroyShaderModule(VkContext.vkDevice(), m, null);
        });
        layout.delete();
    }
}
