package dev.engine_room.flywheel.backend.vk.shader;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

/**
 * A Flywheel graphics pipeline over a {@link VkDescriptorLayout} using dynamic rendering; the opaque draw, the OIT
 * producers, and the fullscreen composite/depth passes differ only by their {@link Config}. Viewport and scissor are dynamic.
 */
public final class VkGraphicsPipeline {
    public static final int COLOR_WRITE_RGBA = VK12.VK_COLOR_COMPONENT_R_BIT | VK12.VK_COLOR_COMPONENT_G_BIT
            | VK12.VK_COLOR_COMPONENT_B_BIT | VK12.VK_COLOR_COMPONENT_A_BIT;
    private static final int INTERNAL_VERTEX_STRIDE = 36;
    private static final int COMPACT_CHUNK_STRIDE = 20;
    private static final int PARTICLE_STRIDE = 28;
    private static final int ENTITY_STRIDE = 36;
    private static final int BLOCK_STRIDE = 28;
    private final VkDescriptorLayout layout;
    private final long vertexModule;
    private final long fragmentModule;
    private final long pipeline;

    public VkGraphicsPipeline(VkDescriptorLayout layout, long vertexModule, long fragmentModule, Config config) {
        this.layout = layout;
        this.vertexModule = vertexModule;
        this.fragmentModule = fragmentModule;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK12.VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule)
                  .pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK12.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule)
                  .pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                                                                                                   .sType$Default();
            if (config.vertex() == Vertex.INTERNAL) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(INTERNAL_VERTEX_STRIDE).inputRate(VK12.VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(5, stack);
                attrs.get(0).location(0).binding(0).format(VK12.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK12.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                attrs.get(2).location(2).binding(0).format(VK12.VK_FORMAT_R32G32_SFLOAT).offset(16);
                attrs.get(3).location(4).binding(0).format(VK12.VK_FORMAT_R16G16_UINT).offset(28);
                attrs.get(4).location(5).binding(0).format(VK12.VK_FORMAT_R8G8B8A8_SNORM).offset(32);

                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs);
            } else if (config.vertex() == Vertex.COMPACT_CHUNK) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(COMPACT_CHUNK_STRIDE).inputRate(VK12.VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(4, stack);
                attrs.get(0).location(0).binding(0).format(VK12.VK_FORMAT_R32G32_UINT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK12.VK_FORMAT_R8G8B8A8_UNORM).offset(8);
                attrs.get(2).location(2).binding(0).format(VK12.VK_FORMAT_R16G16_UINT).offset(12);
                attrs.get(3).location(3).binding(0).format(VK12.VK_FORMAT_R8G8B8A8_UINT).offset(16);

                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs);
            } else if (config.vertex() == Vertex.PARTICLE) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(PARTICLE_STRIDE).inputRate(VK12.VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(4, stack);
                attrs.get(0).location(0).binding(0).format(VK12.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(2).binding(0).format(VK12.VK_FORMAT_R32G32_SFLOAT).offset(12);
                attrs.get(2).location(1).binding(0).format(VK12.VK_FORMAT_R8G8B8A8_UNORM).offset(20);
                attrs.get(3).location(4).binding(0).format(VK12.VK_FORMAT_R16G16_SINT).offset(24);

                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs);
            } else if (config.vertex() == Vertex.ENTITY) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(ENTITY_STRIDE).inputRate(VK12.VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(6, stack);
                attrs.get(0).location(0).binding(0).format(VK12.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK12.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                attrs.get(2).location(2).binding(0).format(VK12.VK_FORMAT_R32G32_SFLOAT).offset(16);
                attrs.get(3).location(3).binding(0).format(VK12.VK_FORMAT_R16G16_SINT).offset(24);
                attrs.get(4).location(4).binding(0).format(VK12.VK_FORMAT_R16G16_SINT).offset(28);
                attrs.get(5).location(5).binding(0).format(VK12.VK_FORMAT_R8G8B8A8_SNORM).offset(32);

                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs);
            } else if (config.vertex() == Vertex.BLOCK) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(BLOCK_STRIDE).inputRate(VK12.VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(4, stack);
                attrs.get(0).location(0).binding(0).format(VK12.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK12.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                attrs.get(2).location(2).binding(0).format(VK12.VK_FORMAT_R32G32_SFLOAT).offset(16);
                attrs.get(3).location(4).binding(0).format(VK12.VK_FORMAT_R16G16_SINT).offset(24);

                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs);
            }

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                                                                                                         .sType$Default()
                                                                                                         .topology(
                                                                                                                 VK12.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                                                                                          .sType$Default()
                                                                                          .viewportCount(1)
                                                                                          .scissorCount(1);

            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                                                                                                  .sType$Default()
                                                                                                  .polygonMode(
                                                                                                          VK12.VK_POLYGON_MODE_FILL)
                                                                                                  .cullMode(
                                                                                                          config.cullMode())
                                                                                                  // Vanilla's VK pipelines use frontFace=CLOCKWISE: the positive-height viewport + Y-flipped projection invert winding vs GL.
                                                                                                  .frontFace(
                                                                                                          VK12.VK_FRONT_FACE_CLOCKWISE)
                                                                                                  .lineWidth(1.0F)
                                                                                                  .depthBiasEnable(
                                                                                                          config.depthBiasConstant() != 0.0F || config.depthBiasSlope() != 0.0F)
                                                                                                  .depthBiasConstantFactor(
                                                                                                          config.depthBiasConstant())
                                                                                                  .depthBiasSlopeFactor(
                                                                                                          config.depthBiasSlope())
                                                                                                  .depthBiasClamp(0.0F);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                                                                                                   .sType$Default()
                                                                                                   .rasterizationSamples(
                                                                                                           VK12.VK_SAMPLE_COUNT_1_BIT);

            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                                                                                               .sType$Default()
                                                                                               .depthTestEnable(
                                                                                                       config.depthTest())
                                                                                               .depthWriteEnable(
                                                                                                       config.depthWrite())
                                                                                               .depthCompareOp(
                                                                                                       config.depthCompareOp());

            int attachmentCount = config.colorFormats().length;
            VkPipelineColorBlendAttachmentState.Buffer blendAttachment = VkPipelineColorBlendAttachmentState.calloc(
                    attachmentCount, stack);
            for (int i = 0; i < attachmentCount; i++) {
                Blend b = config.blends()[i];
                var att = blendAttachment.get(i).colorWriteMask(b.writeMask()).blendEnable(b.enable());
                if (b.enable()) {
                    att.srcColorBlendFactor(b.srcColor()).dstColorBlendFactor(b.dstColor()).colorBlendOp(b.colorOp())
                       .srcAlphaBlendFactor(b.srcAlpha()).dstAlphaBlendFactor(b.dstAlpha()).alphaBlendOp(b.alphaOp());
                }
            }
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                                                                                                .sType$Default()
                                                                                                .pAttachments(
                                                                                                        blendAttachment);

            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                                                                                       .sType$Default()
                                                                                       .pDynamicStates(stack.ints(
                                                                                               VK12.VK_DYNAMIC_STATE_VIEWPORT,
                                                                                               VK12.VK_DYNAMIC_STATE_SCISSOR));

            VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                                                                                         .sType$Default()
                                                                                         .pColorAttachmentFormats(
                                                                                                 stack.ints(
                                                                                                         config.colorFormats()))
                                                                                         .depthAttachmentFormat(
                                                                                                 config.depthFormat());
            if (config.attachmentLocations() != null) {
                VkRenderingInputAttachmentIndexInfoKHR inputIndices = VkRenderingInputAttachmentIndexInfoKHR.calloc(
                                                                                                                    stack)
                                                                                                            .sType$Default()
                                                                                                            .pColorAttachmentInputIndices(
                                                                                                                    stack.ints(
                                                                                                                            config.inputAttachmentIndices()));
                VkRenderingAttachmentLocationInfoKHR locations = VkRenderingAttachmentLocationInfoKHR.calloc(stack)
                                                                                                     .sType$Default()
                                                                                                     .pNext(inputIndices.address())
                                                                                                     .pColorAttachmentLocations(
                                                                                                             stack.ints(
                                                                                                                     config.attachmentLocations()));
                rendering.pNext(locations.address());
            }

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0)
                .sType$Default()
                .pNext(rendering.address())
                .flags(layout.usesDescriptorBuffer()
                        ? EXTDescriptorBuffer.VK_PIPELINE_CREATE_DESCRIPTOR_BUFFER_BIT_EXT : 0)
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly)
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
                throw new IllegalStateException("Vulkan error " + result + " creating graphics pipeline");
            }
            this.pipeline = pPipeline.get(0);
        }
    }

    public static Blend noColorWrite() {
        return new Blend(false, 0, 0, 0, 0, 0, 0, 0);
    }

    public static Blend additive() {
        return new Blend(true, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_OP_ADD,
                VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_OP_ADD, COLOR_WRITE_RGBA);
    }

    public static Blend max() {
        return new Blend(true, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_OP_MAX,
                VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_OP_MAX, COLOR_WRITE_RGBA);
    }

    public static Blend composite() {
        return new Blend(true, VK12.VK_BLEND_FACTOR_SRC_ALPHA, VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                VK12.VK_BLEND_OP_ADD,
                VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK12.VK_BLEND_OP_ADD,
                COLOR_WRITE_RGBA);
    }

    public static Blend premultiplied() {
        return new Blend(true, VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK12.VK_BLEND_OP_ADD,
                VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK12.VK_BLEND_OP_ADD,
                COLOR_WRITE_RGBA);
    }

    public static Blend crumbling() {
        return new Blend(true, VK12.VK_BLEND_FACTOR_DST_COLOR, VK12.VK_BLEND_FACTOR_SRC_COLOR, VK12.VK_BLEND_OP_ADD,
                VK12.VK_BLEND_FACTOR_ONE, VK12.VK_BLEND_FACTOR_ZERO, VK12.VK_BLEND_OP_ADD, COLOR_WRITE_RGBA);
    }

    public static Config material(int colorFormat, int depthFormat, Transparency transparency, DepthTest depthTest,
                                  boolean depthWrite, boolean colorWrite, boolean cull, boolean polygonOffset) {
        return new Config(new int[]{colorFormat}, new Blend[]{blendFor(transparency, colorWrite)}, true, depthWrite,
                compareOp(depthTest), Vertex.INTERNAL, cull ? VK12.VK_CULL_MODE_BACK_BIT : VK12.VK_CULL_MODE_NONE,
                depthFormat,
                polygonOffset ? 10.0F : 0.0F, polygonOffset && transparency != Transparency.OPAQUE ? 1.0F : 0.0F);
    }

    private static Blend blendFor(Transparency transparency, boolean colorWrite) {
        int wm = colorWrite ? COLOR_WRITE_RGBA : 0;
        int one = VK12.VK_BLEND_FACTOR_ONE;
        int zero = VK12.VK_BLEND_FACTOR_ZERO;
        int sa = VK12.VK_BLEND_FACTOR_SRC_ALPHA;
        int omsa = VK12.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        int sc = VK12.VK_BLEND_FACTOR_SRC_COLOR;
        int add = VK12.VK_BLEND_OP_ADD;
        return switch (transparency) {
            case OPAQUE -> new Blend(false, 0, 0, 0, 0, 0, 0, wm);
            case ADDITIVE -> new Blend(true, one, one, add, one, one, add, wm);
            case LIGHTNING -> new Blend(true, sa, one, add, sa, one, add, wm);
            case GLINT -> new Blend(true, sc, one, add, zero, one, add, wm);
            case CRUMBLING, TRANSLUCENT, ORDER_INDEPENDENT -> new Blend(true, sa, omsa, add, one, omsa, add, wm);
        };
    }

    public static int compareOp(DepthTest depthTest) {
        return switch (depthTest) {
            case OFF, ALWAYS -> VK12.VK_COMPARE_OP_ALWAYS;
            case NEVER -> VK12.VK_COMPARE_OP_NEVER;
            case LESS -> VK12.VK_COMPARE_OP_GREATER;
            case EQUAL -> VK12.VK_COMPARE_OP_EQUAL;
            case LEQUAL -> VK12.VK_COMPARE_OP_GREATER_OR_EQUAL;
            case GREATER -> VK12.VK_COMPARE_OP_LESS;
            case NOTEQUAL -> VK12.VK_COMPARE_OP_NOT_EQUAL;
            case GEQUAL -> VK12.VK_COMPARE_OP_LESS_OR_EQUAL;
        };
    }

    public long handle() {
        return pipeline;
    }

    public VkDescriptorLayout layout() {
        return layout;
    }

    public void delete() {
        long p = pipeline;
        long vs = vertexModule;
        long fs = fragmentModule;
        VkContext.deferDestroy(() -> {
            VK12.vkDestroyPipeline(VkContext.vkDevice(), p, null);
            VK12.vkDestroyShaderModule(VkContext.vkDevice(), vs, null);
            VK12.vkDestroyShaderModule(VkContext.vkDevice(), fs, null);
        });
        layout.delete();
    }

    public enum Vertex {NONE, INTERNAL, COMPACT_CHUNK, PARTICLE, ENTITY, BLOCK}

    public record Blend(boolean enable, int srcColor, int dstColor, int colorOp, int srcAlpha, int dstAlpha,
                        int alphaOp, int writeMask) {
    }

    public record Config(int[] colorFormats, Blend[] blends, boolean depthTest, boolean depthWrite, int depthCompareOp,
                         Vertex vertex, int cullMode, int depthFormat, float depthBiasConstant, float depthBiasSlope,
                         int @Nullable [] attachmentLocations, int @Nullable [] inputAttachmentIndices) {
        public Config(int[] colorFormats, Blend[] blends, boolean depthTest, boolean depthWrite, int depthCompareOp,
                      Vertex vertex, int cullMode, int depthFormat) {
            this(colorFormats, blends, depthTest, depthWrite, depthCompareOp, vertex, cullMode, depthFormat, 0.0F, 0.0F,
                    null, null);
        }

        public Config(int[] colorFormats, Blend[] blends, boolean depthTest, boolean depthWrite, int depthCompareOp,
                      Vertex vertex, int cullMode, int depthFormat, float depthBiasConstant, float depthBiasSlope) {
            this(colorFormats, blends, depthTest, depthWrite, depthCompareOp, vertex, cullMode, depthFormat,
                    depthBiasConstant, depthBiasSlope, null, null);
        }

        /**
         * Folded-OIT variant ({@code VK_KHR_dynamic_rendering_local_read}): static per-attachment output locations + input-attachment indices ({@code VK_ATTACHMENT_UNUSED} where this stage neither writes nor reads).
         */
        public Config withLocalRead(int[] locations, int[] inputIndices) {
            return new Config(colorFormats, blends, depthTest, depthWrite, depthCompareOp, vertex, cullMode,
                    depthFormat, depthBiasConstant, depthBiasSlope, locations, inputIndices);
        }
    }
}
