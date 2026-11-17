package dev.engine_room.flywheel.backend.vk.shader;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class VkGraphicsPipeline implements VkMaterialPipeline {
    public static final int COLOR_WRITE_RGBA = VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
            | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT;
    private static final int INTERNAL_VERTEX_STRIDE = 36;
    private static final int COMPACT_CHUNK_STRIDE = 20;
    private static final int PARTICLE_STRIDE = 28;
    private static final int ENTITY_STRIDE = 36;
    private static final int BLOCK_STRIDE = 28;
    // Prefixes per Dynamic constant.
    private static final int[] DYNAMIC_STATES = {VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR,
            VK10.VK_DYNAMIC_STATE_DEPTH_BIAS, EXTExtendedDynamicState.VK_DYNAMIC_STATE_CULL_MODE_EXT,
            EXTExtendedDynamicState.VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE_EXT,
            EXTExtendedDynamicState.VK_DYNAMIC_STATE_DEPTH_COMPARE_OP_EXT,
            EXTExtendedDynamicState3.VK_DYNAMIC_STATE_COLOR_BLEND_ENABLE_EXT,
            EXTExtendedDynamicState3.VK_DYNAMIC_STATE_COLOR_BLEND_EQUATION_EXT,
            EXTExtendedDynamicState3.VK_DYNAMIC_STATE_COLOR_WRITE_MASK_EXT};
    private final VkDescriptorLayout layout;
    private final long vertexModule;
    private final long fragmentModule;
    private final long pipeline;
    private final Config config;
    // By render-pass compatibility identity (null: dynamic rendering), then baked depth state (null: the config's).
    private final Map<@Nullable Subpass, Map<@Nullable DepthState, Long>> variants = new IdentityHashMap<>();

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
                                                                                              .polygonMode(
                                                                                                      VK10.VK_POLYGON_MODE_FILL)
                                                                                              .frontFace(
                                                                                                      VK10.VK_FRONT_FACE_CLOCKWISE)
                                                                                              .lineWidth(1.0F);
        VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                                                                                           .sType$Default()
                                                                                           .depthTestEnable(
                                                                                                   config.depthTest());
        if (config.dynamic() == Dynamic.NONE || config.dynamic() == Dynamic.DEPTH_BIAS) {
            raster.cullMode(config.cullMode());
            depth.depthWriteEnable(config.depthWrite()).depthCompareOp(config.depthCompareOp());
        }
        if (config.dynamic() == Dynamic.NONE) {
            raster.depthBiasEnable(config.depthBiasConstant() != 0.0F || config.depthBiasSlope() != 0.0F)
                  .depthBiasConstantFactor(config.depthBiasConstant())
                  .depthBiasSlopeFactor(config.depthBiasSlope());
        } else {
            // Zero factors draw unbiased; core dynamic depth bias replaces a dynamic enable.
            raster.depthBiasEnable(true);
        }

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
            .pDynamicState(dynamicState(stack, config.dynamic()))
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
        requireAttachments(config.colorFormats(), config.blends(), config.depthFormat(),
                config.dynamic() == Dynamic.MATERIAL_BLEND);
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
        requireAttachments(colorFormats, blends, depthFormat, false);
    }

    /**
     * {@code dynamicBlend}: every attachment may blend whatever its static state.
     */
    private static void requireAttachments(int[] colorFormats, Blend[] blends, int depthFormat,
                                           boolean dynamicBlend) {
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
                if (dynamicBlend || blend.enable()) features |= VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT;
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
        return dynamicState(stack, Dynamic.NONE);
    }

    private static VkPipelineDynamicStateCreateInfo dynamicState(MemoryStack stack, Dynamic dynamic) {
        int count = switch (dynamic) {
            case NONE -> 2;
            case DEPTH_BIAS -> 3;
            case MATERIAL -> 6;
            case MATERIAL_BLEND -> DYNAMIC_STATES.length;
        };
        return VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                                               .pDynamicStates(
                                                       stack.mallocInt(count).put(DYNAMIC_STATES, 0, count).flip());
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

    /**
     * One pipeline for every material state of {@code blend}'s class, set through {@link MaterialState#draw};
     * {@code blend}: {@link #bakedBlend}.
     */
    public static Config material(Vertex vertex, int colorFormat, int depthFormat, @Nullable Blend blend) {
        return new Config(new int[]{colorFormat}, new Blend[]{blend == null ? noColorWrite() : blend}, true, false,
                VK10.VK_COMPARE_OP_ALWAYS, vertex, VK10.VK_CULL_MODE_NONE, depthFormat, 0.0F, 0.0F, null, null,
                blend == null ? Dynamic.MATERIAL_BLEND : Dynamic.material());
    }

    /**
     * The blend class a {@link #material} pipeline bakes for {@code material}; null where the device sets blend
     * dynamically. Disabled color writes form one class, and equal blend functions share theirs.
     */
    public static @Nullable Blend bakedBlend(Material material) {
        if (dynamicBlend()) return null;
        return material.writeMask().color() ? blendFor(material.transparency(), true) : noColorWrite();
    }

    // Dynamic blend extends the dynamic depth state; without VK_EXT_extended_dynamic_state both are baked.
    private static boolean dynamicBlend() {
        return VkCaps.DYNAMIC_COLOR_BLEND_NEGOTIATED && VkCaps.EXTENDED_DYNAMIC_STATE_NEGOTIATED;
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
        return variant(subpass, null);
    }

    @Override
    public long variant(@Nullable Subpass subpass, @Nullable DepthState depth) {
        if (subpass == null && depth == null) return pipeline;
        Map<@Nullable DepthState, Long> byDepth = variants.get(subpass);
        if (byDepth == null) variants.put(subpass, byDepth = new HashMap<>());
        Long handle = byDepth.get(depth);
        if (handle == null) {
            Config variant = subpass == null ? config : subpass.config(config);
            handle = createPipeline(layout, vertexModule, fragmentModule,
                    depth == null ? variant : variant.baked(depth),
                    subpass == null ? 0L : subpass.renderPass(), subpass == null ? 0 : subpass.index());
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
        long vs = vertexModule;
        long fs = fragmentModule;
        long[] variants = this.variants.values().stream().flatMap(byDepth -> byDepth.values().stream())
                                       .mapToLong(Long::longValue).toArray();
        this.variants.clear();
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
                    source.depthBiasSlope(), null, null, source.dynamic());
        }
    }

    public record Blend(boolean enable, int srcColor, int dstColor, int colorOp, int srcAlpha, int dstAlpha,
                        int alphaOp, int writeMask) {
    }

    /**
     * A material's cull mode, depth write and depth compare op, baked into a variant of a {@link Dynamic#DEPTH_BIAS}
     * pipeline where the device sets none of them dynamically; null otherwise.
     */
    public record DepthState(int cullMode, boolean depthWrite, int compareOp) {
        public static @Nullable DepthState draw(Material material) {
            return baked(material, material.writeMask().depth());
        }

        /**
         * Producers and crumbling write no depth.
         */
        public static @Nullable DepthState undepthed(Material material) {
            return baked(material, false);
        }

        private static @Nullable DepthState baked(Material material, boolean depthWrite) {
            if (VkCaps.EXTENDED_DYNAMIC_STATE_NEGOTIATED) return null;
            return new DepthState(material.backfaceCulling() ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE,
                    depthWrite, VkGraphicsPipeline.compareOp(material.depthTest()));
        }
    }

    /**
     * Fixed-function state left to the command buffer; the config's own values for it are ignored.
     */
    public enum Dynamic {
        NONE,
        /**
         * Depth bias factors, with depth bias enabled. Cull mode, depth write and depth compare op come from a
         * {@link DepthState} variant.
         */
        DEPTH_BIAS,
        /**
         * {@link #DEPTH_BIAS} plus cull mode, depth write and depth compare op; depth test stays enabled.
         */
        MATERIAL,
        /**
         * {@link #MATERIAL} plus color blend enable, equation and write mask.
         */
        MATERIAL_BLEND;

        /**
         * Material pipelines with blend baked: {@link #MATERIAL}, or {@link #DEPTH_BIAS} without
         * VK_EXT_extended_dynamic_state.
         */
        public static Dynamic material() {
            return VkCaps.EXTENDED_DYNAMIC_STATE_NEGOTIATED ? MATERIAL : DEPTH_BIAS;
        }
    }

    public record Config(int[] colorFormats, Blend[] blends, boolean depthTest, boolean depthWrite, int depthCompareOp,
                         Vertex vertex, int cullMode, int depthFormat, float depthBiasConstant, float depthBiasSlope,
                         int @Nullable [] attachmentLocations, int @Nullable [] inputAttachmentIndices,
                         Dynamic dynamic) {
        public Config(int[] colorFormats, Blend[] blends, boolean depthTest, boolean depthWrite, int depthCompareOp,
                      Vertex vertex, int cullMode, int depthFormat) {
            this(colorFormats, blends, depthTest, depthWrite, depthCompareOp, vertex, cullMode, depthFormat, 0.0F, 0.0F,
                    null, null, Dynamic.NONE);
        }

        public Config(int[] colorFormats, Blend[] blends, boolean depthTest, boolean depthWrite, int depthCompareOp,
                      Vertex vertex, int cullMode, int depthFormat, float depthBiasConstant, float depthBiasSlope) {
            this(colorFormats, blends, depthTest, depthWrite, depthCompareOp, vertex, cullMode, depthFormat,
                    depthBiasConstant, depthBiasSlope, null, null, Dynamic.NONE);
        }

        public Config(int[] colorFormats, Blend[] blends, boolean depthTest, boolean depthWrite, int depthCompareOp,
                      Vertex vertex, int cullMode, int depthFormat, float depthBiasConstant, float depthBiasSlope,
                      int @Nullable [] attachmentLocations, int @Nullable [] inputAttachmentIndices) {
            this(colorFormats, blends, depthTest, depthWrite, depthCompareOp, vertex, cullMode, depthFormat,
                    depthBiasConstant, depthBiasSlope, attachmentLocations, inputAttachmentIndices, Dynamic.NONE);
        }

        /**
         * Local-read mappings; unused attachments = {@code VK_ATTACHMENT_UNUSED}.
         */
        public Config withLocalRead(int[] locations, int[] inputIndices) {
            return new Config(colorFormats, blends, depthTest, depthWrite, depthCompareOp, vertex, cullMode,
                    depthFormat, depthBiasConstant, depthBiasSlope, locations, inputIndices, dynamic);
        }

        public Config withDynamic(Dynamic dynamic) {
            return new Config(colorFormats, blends, depthTest, depthWrite, depthCompareOp, vertex, cullMode,
                    depthFormat, depthBiasConstant, depthBiasSlope, attachmentLocations, inputAttachmentIndices,
                    dynamic);
        }

        Config baked(DepthState state) {
            return new Config(colorFormats, blends, depthTest, state.depthWrite(), state.compareOp(), vertex,
                    state.cullMode(), depthFormat, depthBiasConstant, depthBiasSlope, attachmentLocations,
                    inputAttachmentIndices, dynamic);
        }
    }

    /**
     * Render thread: the {@link Dynamic#MATERIAL} pipeline bound in one command sequence and the dynamic values recorded
     * since its bind. A sequence starts with {@link #begin}; each draw binds through {@link #rebind}, then calls the
     * setter of the pipeline's family, which records every dynamic value when any differs from the last recorded.
     */
    public static final class MaterialState {
        private long pipeline;
        private boolean recorded;
        private int cullMode;
        private boolean depthWrite;
        private int depthCompareOp;
        private boolean depthBias;
        private float depthBiasConstant;
        private float depthBiasSlope;
        private @Nullable Transparency transparency;
        private boolean colorWrite;

        public void begin() {
            pipeline = 0L;
        }

        /**
         * Whether the caller must bind {@code handle}: it is not the pipeline bound since {@link #begin}.
         */
        public boolean rebind(long handle) {
            if (handle == pipeline) return false;
            pipeline = handle;
            recorded = false;
            return true;
        }

        /**
         * {@link VkGraphicsPipeline#material} pipelines, bound as their {@link DepthState#draw} variant.
         */
        public void draw(VkCommandBuffer cmd, Material material) {
            Transparency transparency = material.transparency();
            DepthTest depthTest = material.depthTest();
            boolean dynamicBlend = dynamicBlend();
            record(cmd, material.backfaceCulling(), material.writeMask().depth(), depthTest,
                    material.polygonOffset(), MaterialRenderState.POLYGON_OFFSET_UNITS,
                    MaterialRenderState.polygonOffsetSlope(transparency, depthTest),
                    dynamicBlend ? transparency : null, dynamicBlend && material.writeMask().color());
        }

        /**
         * Wavelet and insert producers, bound as their {@link DepthState#undepthed} variant.
         */
        public void producer(VkCommandBuffer cmd, Material material) {
            record(cmd, material.backfaceCulling(), false, material.depthTest(), material.polygonOffset(), 10.0F, 1.0F,
                    null, false);
        }

        /**
         * Bound as its {@link DepthState#undepthed} variant.
         */
        public void crumbling(VkCommandBuffer cmd, Material material) {
            record(cmd, material.backfaceCulling(), false, material.depthTest(), true, 10.0F, 1.0F, null, false);
        }

        // Null transparency: the bound pipeline's blend is static.
        private void record(VkCommandBuffer cmd, boolean cull, boolean depthWrite, DepthTest depthTest,
                            boolean depthBias, float depthBiasConstant, float depthBiasSlope,
                            @Nullable Transparency transparency, boolean colorWrite) {
            int cullMode = cull ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE;
            int depthCompareOp = compareOp(depthTest);
            if (recorded && this.cullMode == cullMode && this.depthWrite == depthWrite
                    && this.depthCompareOp == depthCompareOp && this.depthBias == depthBias
                    && this.depthBiasConstant == depthBiasConstant && this.depthBiasSlope == depthBiasSlope
                    && this.transparency == transparency && this.colorWrite == colorWrite) {
                return;
            }
            recorded = true;
            this.cullMode = cullMode;
            this.depthWrite = depthWrite;
            this.depthCompareOp = depthCompareOp;
            this.depthBias = depthBias;
            this.depthBiasConstant = depthBiasConstant;
            this.depthBiasSlope = depthBiasSlope;
            this.transparency = transparency;
            this.colorWrite = colorWrite;
            if (VkCaps.EXTENDED_DYNAMIC_STATE_NEGOTIATED) {
                EXTExtendedDynamicState.vkCmdSetCullModeEXT(cmd, cullMode);
                EXTExtendedDynamicState.vkCmdSetDepthWriteEnableEXT(cmd, depthWrite);
                EXTExtendedDynamicState.vkCmdSetDepthCompareOpEXT(cmd, depthCompareOp);
            }
            VK10.vkCmdSetDepthBias(cmd, depthBias ? depthBiasConstant : 0.0F, 0.0F, depthBias ? depthBiasSlope : 0.0F);
            if (transparency == null) return;
            Blend blend = blendFor(transparency, colorWrite);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var equation = VkColorBlendEquationEXT.calloc(1, stack);
                equation.get(0).srcColorBlendFactor(blend.srcColor()).dstColorBlendFactor(blend.dstColor())
                        .colorBlendOp(blend.colorOp()).srcAlphaBlendFactor(blend.srcAlpha())
                        .dstAlphaBlendFactor(blend.dstAlpha()).alphaBlendOp(blend.alphaOp());
                EXTExtendedDynamicState3.vkCmdSetColorBlendEnableEXT(cmd, 0,
                        stack.ints(blend.enable() ? VK10.VK_TRUE : VK10.VK_FALSE));
                EXTExtendedDynamicState3.vkCmdSetColorBlendEquationEXT(cmd, 0, equation);
                EXTExtendedDynamicState3.vkCmdSetColorWriteMaskEXT(cmd, 0, stack.ints(blend.writeMask()));
            }
        }
    }
}
