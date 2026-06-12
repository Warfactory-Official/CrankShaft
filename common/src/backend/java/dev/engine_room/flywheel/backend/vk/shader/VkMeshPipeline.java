package dev.engine_room.flywheel.backend.vk.shader;

import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

public final class VkMeshPipeline {
    private final VkDescriptorLayout layout;
    private final long taskModule;
    private final long meshModule;
    private final long fragModule;
    private final long pipeline;

    public VkMeshPipeline(VkDescriptorLayout layout, long taskModule, long meshModule, long fragModule,
                          int[] colorFormats, VkGraphicsPipeline.Blend[] blends, boolean depthWrite, int depthFormat) {
        this(layout, taskModule, meshModule, fragModule, colorFormats, blends, depthWrite, depthFormat, null, null,
                0.0F, 0.0F);
    }

    public VkMeshPipeline(VkDescriptorLayout layout, long taskModule, long meshModule, long fragModule,
                          int[] colorFormats, VkGraphicsPipeline.Blend[] blends, boolean depthWrite, int depthFormat,
                          int @Nullable [] attachmentLocations, int @Nullable [] inputAttachmentIndices) {
        this(layout, taskModule, meshModule, fragModule, colorFormats, blends, depthWrite, depthFormat,
                attachmentLocations, inputAttachmentIndices, 0.0F, 0.0F);
    }

    public VkMeshPipeline(VkDescriptorLayout layout, long taskModule, long meshModule, long fragModule,
                          VkGraphicsPipeline.Config config) {
        this(layout, taskModule, meshModule, fragModule, config.colorFormats(), config.blends(), config.depthWrite(),
                config.depthCompareOp(), config.cullMode(), config.depthFormat(), config.attachmentLocations(),
                config.inputAttachmentIndices(), config.depthBiasConstant(), config.depthBiasSlope());
    }

    public VkMeshPipeline(VkDescriptorLayout layout, long taskModule, long meshModule, long fragModule,
                          int[] colorFormats, VkGraphicsPipeline.Blend[] blends, boolean depthWrite, int depthFormat,
                          int @Nullable [] attachmentLocations, int @Nullable [] inputAttachmentIndices,
                          float depthBiasConstant, float depthBiasSlope) {
        this(layout, taskModule, meshModule, fragModule, colorFormats, blends, depthWrite,
                VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VK12.VK_CULL_MODE_BACK_BIT, depthFormat,
                attachmentLocations, inputAttachmentIndices, depthBiasConstant, depthBiasSlope);
    }

    private VkMeshPipeline(VkDescriptorLayout layout, long taskModule, long meshModule, long fragModule,
                           int[] colorFormats, VkGraphicsPipeline.Blend[] blends, boolean depthWrite,
                           int depthCompareOp, int cullMode, int depthFormat,
                           int @Nullable [] attachmentLocations, int @Nullable [] inputAttachmentIndices,
                           float depthBiasConstant, float depthBiasSlope) {
        this.layout = layout;
        this.taskModule = taskModule;
        this.meshModule = meshModule;
        this.fragModule = fragModule;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long requiredSize32 = VkCaps.MESH_SHADER_NEGOTIATED
                    ? VkPipelineShaderStageRequiredSubgroupSizeCreateInfoEXT.calloc(stack)
                                                                            .sType$Default()
                                                                            .requiredSubgroupSize(32)
                                                                            .address()
                    : 0L;
            int stageCount = taskModule != 0L ? 3 : 2;
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(stageCount, stack);
            ByteBuffer entry = stack.UTF8("main");
            int idx = 0;
            if (taskModule != 0L) {
                stages.get(idx++).sType$Default().pNext(requiredSize32)
                      .stage(EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT).module(taskModule).pName(entry);
            }
            stages.get(idx++).sType$Default().pNext(requiredSize32)
                  .stage(EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT).module(meshModule).pName(entry);
            stages.get(idx).sType$Default()
                  .stage(VK12.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(entry);
            stages.position(0);

            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                                                                                          .sType$Default()
                                                                                          .viewportCount(1)
                                                                                          .scissorCount(1);
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                                                                                                  .sType$Default()
                                                                                                  .polygonMode(
                                                                                                          VK12.VK_POLYGON_MODE_FILL)
                                                                                                  .cullMode(cullMode)
                                                                                                  .frontFace(
                                                                                                          VK12.VK_FRONT_FACE_CLOCKWISE)
                                                                                                  .depthBiasEnable(
                                                                                                          depthBiasConstant != 0.0F || depthBiasSlope != 0.0F)
                                                                                                  .depthBiasConstantFactor(
                                                                                                          depthBiasConstant)
                                                                                                  .depthBiasSlopeFactor(
                                                                                                          depthBiasSlope)
                                                                                                  .lineWidth(1.0F);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                                                                                                   .sType$Default()
                                                                                                   .rasterizationSamples(
                                                                                                           VK12.VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                                                                                               .sType$Default()
                                                                                               .depthTestEnable(true)
                                                                                               .depthWriteEnable(
                                                                                                       depthWrite)
                                                                                               .depthCompareOp(
                                                                                                       depthCompareOp);

            VkPipelineColorBlendAttachmentState.Buffer atts = VkPipelineColorBlendAttachmentState.calloc(
                    colorFormats.length, stack);
            for (int i = 0; i < colorFormats.length; i++) {
                VkGraphicsPipeline.Blend b = blends[i];
                var att = atts.get(i).colorWriteMask(b.writeMask()).blendEnable(b.enable());
                if (b.enable()) {
                    att.srcColorBlendFactor(b.srcColor()).dstColorBlendFactor(b.dstColor()).colorBlendOp(b.colorOp())
                       .srcAlphaBlendFactor(b.srcAlpha()).dstAlphaBlendFactor(b.dstAlpha()).alphaBlendOp(b.alphaOp());
                }
            }
            atts.position(0);
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                                                                                                .sType$Default()
                                                                                                .pAttachments(atts);

            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                                                                                       .sType$Default()
                                                                                       .pDynamicStates(stack.ints(
                                                                                               VK12.VK_DYNAMIC_STATE_VIEWPORT,
                                                                                               VK12.VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                                                                                         .sType$Default()
                                                                                         .pColorAttachmentFormats(
                                                                                                 stack.ints(
                                                                                                         colorFormats))
                                                                                         .depthAttachmentFormat(
                                                                                                 depthFormat);
            if (attachmentLocations != null) {
                VkRenderingInputAttachmentIndexInfoKHR inputIndices = VkRenderingInputAttachmentIndexInfoKHR.calloc(
                                                                                                                    stack)
                                                                                                            .sType$Default()
                                                                                                            .pColorAttachmentInputIndices(
                                                                                                                    stack.ints(
                                                                                                                            inputAttachmentIndices));
                VkRenderingAttachmentLocationInfoKHR locations = VkRenderingAttachmentLocationInfoKHR.calloc(stack)
                                                                                                     .sType$Default()
                                                                                                     .pNext(inputIndices.address())
                                                                                                     .pColorAttachmentLocations(
                                                                                                             stack.ints(
                                                                                                                     attachmentLocations));
                rendering.pNext(locations.address());
            }

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0)
                .sType$Default()
                .pNext(rendering.address())
                .flags(layout.usesDescriptorBuffer()
                        ? EXTDescriptorBuffer.VK_PIPELINE_CREATE_DESCRIPTOR_BUFFER_BIT_EXT : 0)
                .pStages(stages)
                .pViewportState(viewport)
                .pRasterizationState(raster)
                .pMultisampleState(multisample)
                .pDepthStencilState(depth)
                .pColorBlendState(colorBlend)
                .pDynamicState(dynamic)
                .layout(layout.pipelineLayout());

            LongBuffer pPipeline = stack.callocLong(1);
            int result = VK12.vkCreateGraphicsPipelines(VkContext.vkDevice(), 0L, info, null, pPipeline);
            if (result != VK12.VK_SUCCESS) {
                throw new IllegalStateException("Vulkan error " + result + " creating mesh-shader pipeline");
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
        long task = taskModule;
        long mesh = meshModule;
        long frag = fragModule;
        VkContext.deferDestroy(() -> {
            VK12.vkDestroyPipeline(VkContext.vkDevice(), p, null);
            if (task != 0L) {
                VK12.vkDestroyShaderModule(VkContext.vkDevice(), task, null);
            }
            VK12.vkDestroyShaderModule(VkContext.vkDevice(), mesh, null);
            VK12.vkDestroyShaderModule(VkContext.vkDevice(), frag, null);
        });
        layout.delete();
    }
}
