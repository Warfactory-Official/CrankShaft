package dev.engine_room.flywheel.backend.vk.shader;

import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

public final class VkMeshPipeline {
    private final VkDescriptorLayout layout;
    private final long taskModule;
    private final long meshModule;
    private final long fragModule;
    private final long pipeline;
    private final VkGraphicsPipeline.Config config;
    private final Map<VkGraphicsPipeline.Subpass, Long> subpassPipelines = new IdentityHashMap<>();

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

    // Material-keyed producers (no depth write): depth test, cull and polygon offset from the material.
    public VkMeshPipeline(VkDescriptorLayout layout, long taskModule, long meshModule, long fragModule,
                          int[] colorFormats, VkGraphicsPipeline.Blend[] blends, int depthCompareOp, int cullMode,
                          int depthFormat, int @Nullable [] attachmentLocations,
                          int @Nullable [] inputAttachmentIndices,
                          float depthBiasConstant, float depthBiasSlope) {
        this(layout, taskModule, meshModule, fragModule, colorFormats, blends, false, depthCompareOp, cullMode,
                depthFormat, attachmentLocations, inputAttachmentIndices, depthBiasConstant, depthBiasSlope);
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
        config = new VkGraphicsPipeline.Config(colorFormats, blends, true, depthWrite, depthCompareOp,
                VkGraphicsPipeline.Vertex.NONE, cullMode, depthFormat, depthBiasConstant, depthBiasSlope,
                attachmentLocations, inputAttachmentIndices);
        pipeline = createPipeline(layout, taskModule, meshModule, fragModule, config, 0L, 0);
    }

    private static long createPipeline(VkDescriptorLayout layout, long taskModule, long meshModule, long fragModule,
                                       VkGraphicsPipeline.Config config, long renderPass, int subpass) {
        VkGraphicsPipeline.preflight(config);
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
            return VkGraphicsPipeline.create(stack,
                    VkGraphicsPipeline.createInfo(stack, layout, config, stages, renderPass, subpass), layout,
                    "mesh-shader pipeline");
        }
    }

    public long handle() {
        return pipeline;
    }

    /** Render thread; variants borrow this owner's modules and layout until the common deferred destruction. */
    public long subpassHandle(VkGraphicsPipeline.Subpass subpass) {
        Long handle = subpassPipelines.get(subpass);
        if (handle == null) {
            handle = createPipeline(layout, taskModule, meshModule, fragModule, subpass.config(config),
                    subpass.renderPass(), subpass.index());
            subpassPipelines.put(subpass, handle);
        }
        return handle;
    }

    public VkDescriptorLayout layout() {
        return layout;
    }

    public void delete() {
        long p = pipeline;
        long task = taskModule;
        long mesh = meshModule;
        long frag = fragModule;
        long[] variants = subpassPipelines.values().stream().mapToLong(Long::longValue).toArray();
        subpassPipelines.clear();
        VkContext.deferDestroy(() -> {
            for (long variant : variants) VK12.vkDestroyPipeline(VkContext.vkDevice(), variant, null);
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
