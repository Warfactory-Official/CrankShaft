package dev.engine_room.flywheel.backend.vk.shader;

import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class VkMeshPipeline implements VkMaterialPipeline {
    private final VkDescriptorLayout layout;
    private final long taskModule;
    private final long meshModule;
    private final long fragModule;
    private final long pipeline;
    private final VkGraphicsPipeline.Config config;
    // As VkGraphicsPipeline's.
    private final Map<VkGraphicsPipeline.@Nullable Subpass, Map<VkGraphicsPipeline.@Nullable DepthState, Long>>
            variants = new IdentityHashMap<>();

    public VkMeshPipeline(VkDescriptorLayout layout, long taskModule, long meshModule, long fragModule,
                          int[] colorFormats, VkGraphicsPipeline.Blend[] blends, boolean depthWrite, int depthFormat) {
        this(layout, taskModule, meshModule, fragModule, colorFormats, blends, depthWrite, depthFormat, null, null);
    }

    public VkMeshPipeline(VkDescriptorLayout layout, long taskModule, long meshModule, long fragModule,
                          int[] colorFormats, VkGraphicsPipeline.Blend[] blends, boolean depthWrite, int depthFormat,
                          int @Nullable [] attachmentLocations, int @Nullable [] inputAttachmentIndices) {
        this(layout, taskModule, meshModule, fragModule, new VkGraphicsPipeline.Config(colorFormats, blends, true,
                depthWrite, VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.NONE,
                VK12.VK_CULL_MODE_BACK_BIT, depthFormat, 0.0F, 0.0F, attachmentLocations, inputAttachmentIndices));
    }

    public VkMeshPipeline(VkDescriptorLayout layout, long taskModule, long meshModule, long fragModule,
                          VkGraphicsPipeline.Config config) {
        this.layout = layout;
        this.taskModule = taskModule;
        this.meshModule = meshModule;
        this.fragModule = fragModule;
        this.config = config;
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
        return variant(subpass, null);
    }

    @Override
    public long variant(VkGraphicsPipeline.@Nullable Subpass subpass,
                        VkGraphicsPipeline.@Nullable DepthState depth) {
        if (subpass == null && depth == null) return pipeline;
        Map<VkGraphicsPipeline.@Nullable DepthState, Long> byDepth = variants.get(subpass);
        if (byDepth == null) variants.put(subpass, byDepth = new HashMap<>());
        Long handle = byDepth.get(depth);
        if (handle == null) {
            VkGraphicsPipeline.Config variant = subpass == null ? config : subpass.config(config);
            handle = createPipeline(layout, taskModule, meshModule, fragModule,
                    depth == null ? variant : variant.baked(depth), subpass == null ? 0L : subpass.renderPass(),
                    subpass == null ? 0 : subpass.index());
            byDepth.put(depth, handle);
        }
        return handle;
    }

    @Override
    public VkDescriptorLayout layout() {
        return layout;
    }

    public void delete() {
        long p = pipeline;
        long task = taskModule;
        long mesh = meshModule;
        long frag = fragModule;
        long[] variants = this.variants.values().stream().flatMap(byDepth -> byDepth.values().stream())
                                       .mapToLong(Long::longValue).toArray();
        this.variants.clear();
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
