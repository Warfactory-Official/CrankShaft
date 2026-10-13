package dev.engine_room.flywheel.backend.vk.shader;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.compile.ProgramAvailability;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.VkPipelineCaches;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

public final class VkGraphicsPipeline {
    public static final int COLOR_WRITE_RGBA = VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
            | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT;
    private static final int INTERNAL_VERTEX_STRIDE = 36;
    private static final int COMPACT_CHUNK_STRIDE = 20;
    private static final int PARTICLE_STRIDE = 28;
    private static final int ENTITY_STRIDE = 36;
    private static final int BLOCK_STRIDE = 28;
    private final VkDescriptorLayout layout;
    private final long vertexModule;
    private final long fragmentModule;
    private final long pipeline;
    private final Config config;
    private final Map<Subpass, Long> subpassPipelines = new IdentityHashMap<>();

    public VkGraphicsPipeline(VkDescriptorLayout layout, long vertexModule, long fragmentModule, Config config) {
        this.layout = layout;
        this.vertexModule = vertexModule;
        this.fragmentModule = fragmentModule;
        this.config = config;
        pipeline = createPipeline(layout, vertexModule, fragmentModule, config, 0L, 0);
    }

    private static long createPipeline(VkDescriptorLayout layout, long vertexModule, long fragmentModule,
                                       Config config, long renderPass, int subpass) {
        preflight(config);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule)
                  .pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule)
                  .pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                                                                                                   .sType$Default();
            if (config.vertex() == Vertex.INTERNAL) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(INTERNAL_VERTEX_STRIDE).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(5, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                attrs.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(16);
                attrs.get(3).location(4).binding(0).format(VK10.VK_FORMAT_R16G16_UINT).offset(28);
                attrs.get(4).location(5).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_SNORM).offset(32);

                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs);
            } else if (config.vertex() == Vertex.COMPACT_CHUNK) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(COMPACT_CHUNK_STRIDE).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(4, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32_UINT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(8);
                attrs.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R16G16_UINT).offset(12);
                attrs.get(3).location(3).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UINT).offset(16);

                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs);
            } else if (config.vertex() == Vertex.PARTICLE) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(PARTICLE_STRIDE).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(4, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(2).binding(0).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(12);
                attrs.get(2).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(20);
                attrs.get(3).location(4).binding(0).format(VK10.VK_FORMAT_R16G16_SINT).offset(24);

                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs);
            } else if (config.vertex() == Vertex.ENTITY) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(ENTITY_STRIDE).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(6, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                attrs.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(16);
                attrs.get(3).location(3).binding(0).format(VK10.VK_FORMAT_R16G16_SINT).offset(24);
                attrs.get(4).location(4).binding(0).format(VK10.VK_FORMAT_R16G16_SINT).offset(28);
                attrs.get(5).location(5).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_SNORM).offset(32);

                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs);
            } else if (config.vertex() == Vertex.BLOCK || config.vertex() == Vertex.BLOCK_MULTIDRAW) {
                boolean multidraw = config.vertex() == Vertex.BLOCK_MULTIDRAW;
                VkVertexInputBindingDescription.Buffer binding =
                        VkVertexInputBindingDescription.calloc(multidraw ? 2 : 1, stack);
                binding.get(0).binding(0).stride(BLOCK_STRIDE).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attrs =
                        VkVertexInputAttributeDescription.calloc(multidraw ? 6 : 4, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                attrs.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(16);
                attrs.get(3).location(4).binding(0).format(VK10.VK_FORMAT_R16G16_SINT).offset(24);
                if (multidraw) {
                    // Port: vanilla's prepared firstInstance selects one 16-byte section record per command.
                    binding.get(1).binding(1).stride(16).inputRate(VK10.VK_VERTEX_INPUT_RATE_INSTANCE);
                    attrs.get(4).location(6).binding(1).format(VK10.VK_FORMAT_R32G32B32_SINT).offset(0);
                    attrs.get(5).location(7).binding(1).format(VK10.VK_FORMAT_R32_SFLOAT).offset(12);
                }

                vertexInput.pVertexBindingDescriptions(binding).pVertexAttributeDescriptions(attrs);
            }
            VkCaps.requireVertexInput(vertexInput);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                                                                                                         .sType$Default()
                                                                                                         .topology(
                                                                                                                 VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            VkGraphicsPipelineCreateInfo.Buffer info = createInfo(stack, layout, config, stages, renderPass, subpass);
            info.get(0).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly);
            return create(stack, info, layout, "graphics pipeline");
        }
    }

    /** Stage-independent state; graphics adds vertex input and input assembly. */
    static VkGraphicsPipelineCreateInfo.Buffer createInfo(MemoryStack stack, VkDescriptorLayout layout, Config config,
                                                          VkPipelineShaderStageCreateInfo.Buffer stages,
                                                          long renderPass, int subpass) {
        VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default()
                .polygonMode(VK10.VK_POLYGON_MODE_FILL)
                .cullMode(config.cullMode())
                .frontFace(VK10.VK_FRONT_FACE_CLOCKWISE)
                .lineWidth(1.0F)
                .depthBiasEnable(config.depthBiasConstant() != 0.0F || config.depthBiasSlope() != 0.0F)
                .depthBiasConstantFactor(config.depthBiasConstant())
                .depthBiasSlopeFactor(config.depthBiasSlope());
        VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthTestEnable(config.depthTest())
                .depthWriteEnable(config.depthWrite())
                .depthCompareOp(config.depthCompareOp());

        VkPipelineRenderingCreateInfoKHR rendering = renderingInfo(stack, config.colorFormats(), config.depthFormat());
        if (config.attachmentLocations() != null) {
            VkRenderingInputAttachmentIndexInfoKHR inputIndices = VkRenderingInputAttachmentIndexInfoKHR.calloc(stack)
                    .sType$Default().pColorAttachmentInputIndices(stack.ints(config.inputAttachmentIndices()));
            rendering.pNext(VkRenderingAttachmentLocationInfoKHR.calloc(stack).sType$Default()
                    .pNext(inputIndices.address())
                    .pColorAttachmentLocations(stack.ints(config.attachmentLocations())).address());
        }

        VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        info.get(0)
            .sType$Default()
            .pNext(renderPass == 0L ? rendering.address() : 0L)
            .flags(layout.usesDescriptorBuffer() ? EXTDescriptorBuffer.VK_PIPELINE_CREATE_DESCRIPTOR_BUFFER_BIT_EXT : 0)
            .pStages(stages)
            .pViewportState(viewportState(stack))
            .pRasterizationState(raster)
            .pMultisampleState(multisampleState(stack))
            .pDepthStencilState(depth)
            .pColorBlendState(colorBlendState(stack, config.blends()))
            .pDynamicState(dynamicState(stack))
            .layout(layout.pipelineLayout())
            .renderPass(renderPass)
            .subpass(subpass);
        return info;
    }

    static long create(MemoryStack stack, VkGraphicsPipelineCreateInfo.Buffer info, VkDescriptorLayout layout,
                       String operation) {
        LongBuffer pPipeline = stack.callocLong(1);
        int result = VK10.vkCreateGraphicsPipelines(VkContext.vkDevice(), VkPipelineCaches.handle(), info, null,
                pPipeline);
        try {
            VkCaps.checkPipelineResult(result, operation);
        } catch (BackendUnavailableException e) {
            if (layout.usesDescriptorBuffer() && layout.pushFallbackAvailable()) {
                throw new ProgramAvailability.Failure(ProgramAvailability.Feature.DESCRIPTOR_BUFFER, e);
            }
            throw e;
        }
        return pPipeline.get(0);
    }

    static void preflight(Config config) {
        int attachments = config.colorFormats().length;
        requireAttachments(config.colorFormats(), config.blends(), config.depthFormat());
        if (config.attachmentLocations() != null) {
            if (config.attachmentLocations().length != attachments || config.inputAttachmentIndices() == null
                    || config.inputAttachmentIndices().length != attachments) {
                throw new IllegalArgumentException("Local-read attachment mappings differ in length");
            }
            if (!VkCaps.DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED) {
                throw new BackendUnavailableException("Dynamic rendering local read is not enabled");
            }
            requireAttachmentIndices("Output location", config.attachmentLocations(), VkCaps.MAX_COLOR_ATTACHMENTS);
            requireAttachmentIndices("Input attachment", config.inputAttachmentIndices(),
                    VkCaps.MAX_PER_STAGE_DESCRIPTOR_INPUT_ATTACHMENTS);
        } else if (config.inputAttachmentIndices() != null) {
            throw new IllegalArgumentException("Input attachment indices without output locations");
        }
    }

    /** Device limits and format features; {@code VK_FORMAT_UNDEFINED} = unused color slot. */
    public static void requireAttachments(int[] colorFormats, Blend[] blends, int depthFormat) {
        int attachments = colorFormats.length;
        if (blends.length != attachments) {
            throw new IllegalArgumentException("Color formats and blend states differ in length");
        }
        if (attachments > VkCaps.MAX_COLOR_ATTACHMENTS) {
            throw new BackendUnavailableException("Graphics pipeline requires " + attachments
                    + " color attachments; device limit " + VkCaps.MAX_COLOR_ATTACHMENTS);
        }
        for (int i = 0; i < attachments; i++) {
            Blend blend = blends[i];
            if (i > 0 && !VkCaps.INDEPENDENT_BLEND_NEGOTIATED && !blend.equals(blends[0])) {
                throw new BackendUnavailableException("Independent attachment blending is not enabled");
            }
            if (colorFormats[i] != VK10.VK_FORMAT_UNDEFINED) {
                int features = VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
                if (blend.enable()) features |= VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT;
                VkCaps.requireFormat("Color attachment", colorFormats[i], features);
            }
        }
        if (depthFormat != VK10.VK_FORMAT_UNDEFINED) {
            VkCaps.requireFormat("Depth attachment", depthFormat, VK10.VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT);
        }
    }

    public static VkPipelineViewportStateCreateInfo viewportState(MemoryStack stack) {
        return VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
    }

    public static VkPipelineDynamicStateCreateInfo dynamicState(MemoryStack stack) {
        return VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                .pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));
    }

    public static VkPipelineMultisampleStateCreateInfo multisampleState(MemoryStack stack) {
        return VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                .rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);
    }

    public static VkPipelineColorBlendStateCreateInfo colorBlendState(MemoryStack stack, Blend[] blends) {
        VkPipelineColorBlendAttachmentState.Buffer attachments =
                VkPipelineColorBlendAttachmentState.calloc(blends.length, stack);
        for (int i = 0; i < blends.length; i++) {
            Blend b = blends[i];
            var att = attachments.get(i).colorWriteMask(b.writeMask()).blendEnable(b.enable());
            if (b.enable()) {
                att.srcColorBlendFactor(b.srcColor()).dstColorBlendFactor(b.dstColor()).colorBlendOp(b.colorOp())
                   .srcAlphaBlendFactor(b.srcAlpha()).dstAlphaBlendFactor(b.dstAlpha()).alphaBlendOp(b.alphaOp());
            }
        }
        return VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(attachments);
    }

    public static VkPipelineRenderingCreateInfoKHR renderingInfo(MemoryStack stack, int[] colorFormats,
            int depthFormat) {
        return VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
                .pColorAttachmentFormats(stack.ints(colorFormats)).depthAttachmentFormat(depthFormat)
                .stencilAttachmentFormat(VkContext.stencilFormat(depthFormat));
    }

    private static void requireAttachmentIndices(String label, int[] indices, long limit) {
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            if (index == VK10.VK_ATTACHMENT_UNUSED) continue;
            if (index < 0) throw new IllegalArgumentException(label + " is negative: " + index);
            if (index >= limit) {
                throw new BackendUnavailableException(label + " " + index + " exceeds device limit " + limit);
            }
            for (int j = 0; j < i; j++) {
                if (indices[j] == index) throw new IllegalArgumentException("Duplicate " + label + ": " + index);
            }
        }
    }

    public static Blend noColorWrite() {
        return new Blend(false, 0, 0, 0, 0, 0, 0, 0);
    }

    public static Blend additive() {
        return new Blend(true, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_OP_ADD,
                VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_OP_ADD, COLOR_WRITE_RGBA);
    }

    public static Blend max() {
        return new Blend(true, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_OP_MAX,
                VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_OP_MAX, COLOR_WRITE_RGBA);
    }

    public static Blend composite() {
        return new Blend(true, VK10.VK_BLEND_FACTOR_SRC_ALPHA, VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                VK10.VK_BLEND_OP_ADD,
                VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK10.VK_BLEND_OP_ADD,
                COLOR_WRITE_RGBA);
    }

    public static Blend premultiplied() {
        return new Blend(true, VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK10.VK_BLEND_OP_ADD,
                VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK10.VK_BLEND_OP_ADD,
                COLOR_WRITE_RGBA);
    }

    public static Blend crumbling() {
        return new Blend(true, VK10.VK_BLEND_FACTOR_DST_COLOR, VK10.VK_BLEND_FACTOR_SRC_COLOR, VK10.VK_BLEND_OP_ADD,
                VK10.VK_BLEND_FACTOR_ONE, VK10.VK_BLEND_FACTOR_ZERO, VK10.VK_BLEND_OP_ADD, COLOR_WRITE_RGBA);
    }

    public static Config material(int colorFormat, int depthFormat, Transparency transparency, DepthTest depthTest,
                                  boolean depthWrite, boolean colorWrite, boolean cull, boolean polygonOffset) {
        return new Config(new int[]{colorFormat}, new Blend[]{blendFor(transparency, colorWrite)}, true, depthWrite,
                compareOp(depthTest), Vertex.INTERNAL, cull ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE,
                depthFormat,
                polygonOffset ? MaterialRenderState.POLYGON_OFFSET_UNITS : 0.0F,
                polygonOffset ? MaterialRenderState.polygonOffsetSlope(transparency, depthTest) : 0.0F);
    }

    private static Blend blendFor(Transparency transparency, boolean colorWrite) {
        int wm = colorWrite ? COLOR_WRITE_RGBA : 0;
        int one = VK10.VK_BLEND_FACTOR_ONE;
        int zero = VK10.VK_BLEND_FACTOR_ZERO;
        int sa = VK10.VK_BLEND_FACTOR_SRC_ALPHA;
        int omsa = VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        int sc = VK10.VK_BLEND_FACTOR_SRC_COLOR;
        int add = VK10.VK_BLEND_OP_ADD;
        return switch (transparency) {
            case OPAQUE -> new Blend(false, 0, 0, 0, 0, 0, 0, wm);
            case ADDITIVE -> new Blend(true, one, one, add, one, one, add, wm);
            case LIGHTNING, ORDER_INDEPENDENT_ADDITIVE -> new Blend(true, sa, one, add, sa, one, add, wm);
            case GLINT -> new Blend(true, sc, one, add, zero, one, add, wm);
            case CRUMBLING, TRANSLUCENT, ORDER_INDEPENDENT -> new Blend(true, sa, omsa, add, one, omsa, add, wm);
            case TRANSLUCENT_ALPHA_REPLACE -> new Blend(true, sa, omsa, add, one, zero, add, wm);
        };
    }

    public static int compareOp(DepthTest depthTest) {
        return switch (depthTest) {
            case OFF, ALWAYS -> VK10.VK_COMPARE_OP_ALWAYS;
            case NEVER -> VK10.VK_COMPARE_OP_NEVER;
            case LESS -> VK10.VK_COMPARE_OP_GREATER;
            case EQUAL -> VK10.VK_COMPARE_OP_EQUAL;
            case LEQUAL -> VK10.VK_COMPARE_OP_GREATER_OR_EQUAL;
            case GREATER -> VK10.VK_COMPARE_OP_LESS;
            case NOTEQUAL -> VK10.VK_COMPARE_OP_NOT_EQUAL;
            case GEQUAL -> VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
        };
    }

    public long handle() {
        return pipeline;
    }

    /** Render thread; the original owner retains the modules, layout and every compatible variant. */
    public long subpassHandle(Subpass subpass) {
        Long handle = subpassPipelines.get(subpass);
        if (handle == null) {
            handle = createPipeline(layout, vertexModule, fragmentModule, subpass.config(config),
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
        long vs = vertexModule;
        long fs = fragmentModule;
        long[] variants = subpassPipelines.values().stream().mapToLong(Long::longValue).toArray();
        subpassPipelines.clear();
        VkContext.deferDestroy(() -> {
            for (long variant : variants) VK10.vkDestroyPipeline(VkContext.vkDevice(), variant, null);
            VK10.vkDestroyPipeline(VkContext.vkDevice(), p, null);
            VK10.vkDestroyShaderModule(VkContext.vkDevice(), vs, null);
            VK10.vkDestroyShaderModule(VkContext.vkDevice(), fs, null);
        });
        layout.delete();
    }

    public enum Vertex {NONE, INTERNAL, COMPACT_CHUNK, PARTICLE, ENTITY, BLOCK, BLOCK_MULTIDRAW}

    /** A stable pass-owned key; identity distinguishes render passes even if Vulkan recycles their handles. */
    public record Subpass(long renderPass, int index, int depthFormat, int[] colorFormats,
                          int[] sourceColorAttachments) {
        public Subpass {
            colorFormats = colorFormats.clone();
            sourceColorAttachments = sourceColorAttachments.clone();
            if (colorFormats.length != sourceColorAttachments.length) {
                throw new IllegalArgumentException("Subpass color formats and source mappings differ in length");
            }
        }

        public Config config(Config source) {
            Blend[] blends = new Blend[sourceColorAttachments.length];
            for (int i = 0; i < blends.length; i++) blends[i] = source.blends()[sourceColorAttachments[i]];
            return new Config(colorFormats, blends, source.depthTest(), source.depthWrite(), source.depthCompareOp(),
                    source.vertex(), source.cullMode(), depthFormat, source.depthBiasConstant(),
                    source.depthBiasSlope());
        }
    }

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
         * Local-read mappings; unused attachments = {@code VK_ATTACHMENT_UNUSED}.
         */
        public Config withLocalRead(int[] locations, int[] inputIndices) {
            return new Config(colorFormats, blends, depthTest, depthWrite, depthCompareOp, vertex, cullMode,
                    depthFormat, depthBiasConstant, depthBiasSlope, locations, inputIndices);
        }
    }
}
