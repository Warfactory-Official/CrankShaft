package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import dev.engine_room.flywheel.backend.compile.NativeOitProducers.Variant;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.buffer.VkBuffer;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorHeap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Native producer variants for exact Vulkan compatibility: a fused graph's subpass or a separate dynamic-rendering
 * pass. All scope changes and GPU publication run on the render thread. The pass owner supplies real input
 * attachments and depth formats; native uniforms and push constants continue through Renderpearl's ordinary
 * frontend/backend contract.
 */
public final class VkNativeOitPipelines {
    // Engine slots of oit_producer.glsl and mlab.glsl, moved above the native uniform bindings.
    private static final int DEPTH_RANGE = VkNativePipelineCompiler.ENGINE_BINDING_BASE + 14;
    private static final int BLUE_NOISE = VkNativePipelineCompiler.ENGINE_BINDING_BASE + 15;
    private static final int OIT_SLOT_0 = VkNativePipelineCompiler.ENGINE_BINDING_BASE + 24;
    private static final int OIT_SLOT_1 = OIT_SLOT_0 + 1;
    private static final int OIT_SLOT_2 = OIT_SLOT_0 + 2;
    private static final int OIT_SLOT_3 = OIT_SLOT_0 + 3;
    private static final String UTILITY_INPUT = "_flw_nativeUtilityInput";
    private static final Map<ContextKey, PreparedContext> CONTEXTS = new HashMap<>();
    private static final Map<Identifier, ShaderSource.CachedIncludeSource> INCLUDES = new ConcurrentHashMap<>();

    private VkNativeOitPipelines() {
    }

    static PreparedContext subpassContext(NativeOitScope scope) {
        RenderSystem.assertOnRenderThread();
        return context(scope.variant, scope.localRead(), scope.owner.compatibility());
    }

    /** A separate native pass, right after its creation; later producer lookups target its exact attachments. */
    public static void bindPass(List<@Nullable GpuTextureView> colors, GpuTextureView depth) {
        RenderSystem.assertOnRenderThread();
        NativeOitScope scope = Objects.requireNonNull(NativeOitScope.active(),
                "Separate native Vulkan pass outside a producer scope");
        if (scope.owner != null) throw new IllegalStateException("Separate native Vulkan pass inside a fused subpass");
        List<@Nullable GpuFormat> formats = new ArrayList<>(colors.size());
        for (GpuTextureView view : colors) formats.add(view == null ? null : view.texture().getFormat());
        scope.context = context(scope.variant, false,
                separateCompatibility(scope.variant, formats, VulkanConst.toVk(depth.texture().getFormat())));
        scope.selected = null;
    }

    /**
     * Subpasses need a compatible PSO for every pipeline, native utilities included. Separate passes adapt only
     * producers; vanilla compiles its own utilities for its own passes.
     */
    static @Nullable CompiledRenderPipeline select(NativeOitScope scope, RenderPipeline base) {
        if (scope.owner == null) {
            if (!NativeOitProducers.isProducer(base.getShaderDefines())) return null;
            if (scope.context == null) {
                throw new IllegalStateException("Native Vulkan producer selected outside its pass: " + base);
            }
        }
        PreparedPipeline selected = prepare(base, scope.context);
        NativeOitScope.selected(selected.additive);
        scope.selected = selected.compiled;
        return selected.compiled.frontend();
    }

    /** Allows loading/configuration warmup to populate the same context that producer draws select. */
    public static void precompileWavelet(RenderPipeline base, OitMode mode, boolean emission, boolean localRead,
                                         VkNativePipelineCompiler.Compatibility compatibility) {
        RenderSystem.assertOnRenderThread();
        prepare(base, context(Variant.wavelet(mode, emission), localRead, compatibility));
    }

    public static void precompileInsert(RenderPipeline base, OitInsertMode mode,
                                        VkNativePipelineCompiler.Compatibility compatibility) {
        RenderSystem.assertOnRenderThread();
        prepare(base, context(Variant.insert(mode), false, compatibility));
    }

    /** Separate-pass warmup: the engine chain's attachment formats and one producer depth attachment format. */
    public static void precompileSeparate(RenderPipeline base, OitMode mode, boolean emission,
                                          @Nullable OitInsertMode insert, int depthFormat) {
        RenderSystem.assertOnRenderThread();
        Variant variant = insert != null ? Variant.insert(insert) : Variant.wavelet(mode, emission);
        List<@Nullable GpuFormat> colors = switch (variant) {
            case DEPTH -> List.of(OitFramebuffer.DEPTH_BOUNDS_FORMAT);
            case COEFFICIENTS -> Collections.nCopies(4, OitFramebuffer.COEFFICIENTS_FORMAT);
            case ACCUMULATE, EMISSION -> List.of(OitFramebuffer.ACCUMULATE_FORMAT);
            case KBUFFER, MLAB, ABUFFER, ORIGINAL -> List.of();
        };
        prepare(base, context(variant, false, separateCompatibility(variant, colors, depthFormat)));
    }

    private static VkNativePipelineCompiler.Compatibility separateCompatibility(Variant variant,
                                                                                List<@Nullable GpuFormat> colors,
                                                                                int depthFormat) {
        Optional<BlendFunction> blend = variant.insert != null ? Optional.empty()
                : Optional.of(variant == Variant.DEPTH ? BlendFunction.MAX : BlendFunction.ADDITIVE);
        List<@Nullable ColorTargetState> targets = new ArrayList<>(colors.size());
        for (GpuFormat format : colors) {
            targets.add(format == null ? null : new ColorTargetState(blend, format, ColorTargetState.WRITE_ALL));
        }
        return new VkNativePipelineCompiler.Compatibility(0L, 0, depthFormat, targets);
    }

    private static PreparedContext context(Variant variant, boolean localRead,
                                           VkNativePipelineCompiler.Compatibility compatibility) {
        return CONTEXTS.computeIfAbsent(new ContextKey(variant, localRead, compatibility), PreparedContext::new);
    }

    private static PreparedPipeline prepare(RenderPipeline base, PreparedContext context) {
        PreparedPipeline cached = context.pipelines.get(base);
        if (cached != null) return cached;
        boolean producer = NativeOitProducers.isProducer(base.getShaderDefines());
        Variant variant = producer ? context.key.variant : Variant.ORIGINAL;
        if (producer) variant.requireStage(base);
        var compatibility = context.key.compatibility;
        var exact = new VkNativePipelineCompiler.Compatibility(compatibility.renderPass(), compatibility.subpass(),
                compatibility.depthFormat(), pipelineColors(base, variant, compatibility.colorTargets()));
        Utility utility = utility(base);
        var adapted = new AdaptedPipeline(base, variant, exact, utility);
        try (var compiler = new VkNativeFrontendCompiler()) {
            var compiled = compiler.compile(adapted, new NativeSource(variant, context.key.localRead, utility),
                    engineBindings(context.key.localRead), exact);
            PreparedPipeline selected = new PreparedPipeline(compiled,
                    producer && NativeOitProducers.defined(base.getShaderDefines(), NativeOitProducers.ADDITIVE));
            context.pipelines.put(base, selected);
            return selected;
        }
    }

    private static List<ColorTargetState> pipelineColors(RenderPipeline base, Variant variant,
                                                         List<ColorTargetState> targets) {
        List<ColorTargetState> nativeColors = base.getColorTargetStates();
        List<ColorTargetState> result = new ArrayList<>(targets.size());
        int expected = switch (variant) {
            case DEPTH, ACCUMULATE, EMISSION -> 1;
            case COEFFICIENTS -> 4;
            case KBUFFER, MLAB, ABUFFER -> 0;
            case ORIGINAL -> nativeColors.size();
        };
        if (expected > targets.size()) {
            throw new IllegalStateException("Native producer has more outputs than its subpass: " + base);
        }
        for (int i = 0; i < targets.size(); i++) {
            var target = targets.get(i);
            if (target == null) {
                result.add(null);
                continue;
            }
            ColorTargetState state = i >= expected ? null : variant == Variant.ORIGINAL ? nativeColors.get(i) : target;
            result.add(new ColorTargetState(state == null ? Optional.empty() : state.blendFunction(), target.format(),
                    state == null ? ColorTargetState.WRITE_NONE : state.writeMask()));
        }
        return result;
    }

    private static List<BindGroupLayout> layouts(RenderPipeline base, Variant variant, Utility utility) {
        boolean producer = variant != Variant.ORIGINAL;
        return NativeOitProducers.layouts(base, name -> producer && NativeOitProducers.NATIVE_READS.contains(name)
                || utility != Utility.NONE && utility.sampler.equals(name), producer);
    }

    private static Map<String, VkNativePipelineCompiler.EngineBinding> engineBindings(boolean localRead) {
        int read = localRead ? VK12.VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT
                : VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        int sampler = VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        int storage = VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        return Map.ofEntries(
                binding("_flw_depthRange", DEPTH_RANGE, read),
                binding("_flw_blueNoise", BLUE_NOISE, sampler),
                binding("_flw_coefficients0", OIT_SLOT_0, read),
                binding("_flw_coefficients1", OIT_SLOT_1, read),
                binding("_flw_coefficients2", OIT_SLOT_2, read),
                binding("_flw_coefficients3", OIT_SLOT_3, read),
                binding("_FlwMlabCount", OIT_SLOT_0, storage),
                binding("_FlwMlabData", OIT_SLOT_1, storage),
                binding("_FlwMlabUniforms", OIT_SLOT_2, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER),
                binding("_FlwAbufHead", OIT_SLOT_0, storage),
                binding("_FlwAbufNodes", OIT_SLOT_1, storage),
                binding("_FlwAbufCounter", OIT_SLOT_3, storage),
                binding(UTILITY_INPUT, DEPTH_RANGE, VK12.VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT));
    }

    private static Map.Entry<String, VkNativePipelineCompiler.EngineBinding> binding(String name, int binding,
            int type) {
        return Map.entry(name, new VkNativePipelineCompiler.EngineBinding(binding, type));
    }

    /** Called after native descriptor pushes, including draws whose native uniform list was already clean. */
    public static void pushDescriptors(VkCommandBuffer commandBuffer, VulkanRenderPipeline pipeline) {
        NativeOitScope scope = NativeOitScope.active();
        if (scope == null || scope.selected == null || scope.selected.backend() != pipeline) return;
        var resources = scope.selected.engineResources();
        if (resources.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(resources.size(), stack);
            for (int i = 0; i < resources.size(); i++) {
                var resource = resources.get(i);
                var write = writes.get(i).sType$Default().dstBinding(resource.binding()).descriptorCount(1)
                        .descriptorType(resource.descriptorType());
                if (resource.descriptorType() == VK12.VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT) {
                    write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(VkContext.imageView(scope.owner.inputViews()[resource.inputAttachmentIndex()]))
                            .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL));
                } else if (resource.descriptorType() == VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER) {
                    GpuTextureView view;
                    long sampler;
                    if (resource.binding() == BLUE_NOISE) {
                        view = scope.frame.blueNoiseView();
                        sampler = VkContext.sampler(scope.frame.noiseSampler());
                    } else {
                        view = resource.binding() == DEPTH_RANGE ? scope.framebuffer.depthBoundsView()
                                : scope.framebuffer.coefficientsView(resource.binding() - OIT_SLOT_0);
                        sampler = VkContext.sampler(scope.frame.oitSampler());
                    }
                    write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(VkContext.imageView(view))
                            .sampler(sampler).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL));
                } else {
                    VkBuffer buffer = switch (resource.binding()) {
                        case OIT_SLOT_0 -> scope.storage.buf24();
                        case OIT_SLOT_1 -> scope.storage.buf25();
                        case OIT_SLOT_2 -> scope.storage.ubo();
                        case OIT_SLOT_3 -> scope.storage.counter();
                        default -> throw new IllegalStateException("Unknown native engine buffer " + resource.name());
                    };
                    write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.vkBuffer())
                            .offset(0).range(buffer.sizeBytes()));
                }
            }
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_GRAPHICS,
                    scope.selected.pipelineLayout(), 0, writes);
        }
        VkDescriptorHeap.notifyForeignBind();
    }

    /** Resource reload and render-pass graph destruction close all variants before native handles are reused. */
    public static void reset() {
        RenderSystem.assertOnRenderThread();
        if (NativeOitScope.active() != null) {
            throw new IllegalStateException("Cannot reset native Vulkan variants during replay");
        }
        CONTEXTS.values().forEach(context -> context.pipelines.values().forEach(pipeline -> pipeline.compiled.close()));
        CONTEXTS.clear();
        NativeOitProducers.reset();
        INCLUDES.values().forEach(ShaderSource.CachedIncludeSource::close);
        INCLUDES.clear();
    }

    private static Utility utility(RenderPipeline base) {
        if (base == RenderPipelines.BLIT_DEPTH_BOUNDS || base == RenderPipelines.BLIT_DEPTH
                || base == RenderPipelines.BLIT_DEPTH_DURING_DEPTH_BOUNDS) return Utility.IN_SAMPLER;
        if (base == RenderPipelines.OIT_DEPTH_BOUNDS_CULL) return Utility.DEPTH_BOUNDS;
        return Utility.NONE;
    }

    private static final class NativeSource implements ShaderSource {
        // Link the reusable cache loader while constructing the source, before shaderc can invoke getInclude.
        private static final Function<Identifier, CachedIncludeSource> INCLUDE_LOADER = NativeSource::loadInclude;
        private final Variant variant;
        private final boolean localRead;
        private final Utility utility;

        private NativeSource(Variant variant, boolean localRead, Utility utility) {
            this.variant = variant;
            this.localRead = localRead;
            this.utility = utility;
        }

        @Override
        public String getShader(Identifier id, ShaderType type) {
            Compilation compilation = new Compilation();
            compilation.version(ShaderAssembly.glslVersion());
            if (type == ShaderType.FRAGMENT && variant != Variant.ORIGINAL) {
                NativeOitProducers.assembleFragment(compilation, variant, localRead, false, id);
                return compilation.assembledSource();
            }
            String raw = NativeOitProducers.rawShader(id, type);
            if (type == ShaderType.FRAGMENT && utility != Utility.NONE) {
                String declaration = "uniform sampler2D " + utility.sampler + ";";
                if (!raw.contains(declaration)) {
                    throw new IllegalStateException("Native input utility shader changed: " + id);
                }
                raw = raw.replace(declaration, "");
                compilation.define(utility.sampler, UTILITY_INPUT);
                compilation.define("texture(nativeTexture, nativeUv)", "subpassLoad(nativeTexture)");
                compilation.define("texelFetch(nativeTexture, nativePixel, nativeLod)", "subpassLoad(nativeTexture)");
                compilation.appendComponent(new ShaderAssembly.RawSource("native_utility_input",
                        "layout(input_attachment_index = 0, set = 0, binding = 14) uniform subpassInput "
                                + UTILITY_INPUT + ";"));
            }
            compilation.appendComponent(new ShaderAssembly.RawSource(id.toString(), raw));
            return compilation.assembledSource();
        }

        @Override
        public CachedIncludeSource getInclude(Identifier id) {
            return INCLUDES.computeIfAbsent(id, INCLUDE_LOADER);
        }

        private static CachedIncludeSource loadInclude(Identifier id) {
            try (var stream = Minecraft.getInstance().getResourceManager()
                    .getResource(id.withPrefix("shaders/include/")).orElseThrow().open()) {
                return CachedIncludeSource.create(id, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException failure) {
                // Native callbacks return an error result; exceptions cannot cross the shaderc upcall boundary.
                return CachedIncludeSource.createError(new StringBuilder("Could not read native Vulkan shader include ")
                        .append(id).append(": ").append(failure.getMessage()).toString());
            }
        }

        @Override
        public void close() {
        }
    }

    private static final class AdaptedPipeline extends RenderPipeline {
        private AdaptedPipeline(RenderPipeline base, Variant variant,
                                VkNativePipelineCompiler.Compatibility compatibility,
                                Utility utility) {
            super(NativeOitProducers.location("native_oit_vk", variant, base), base.getShaders(),
                    base.getShaderDefines(), layouts(base, variant, utility),
                    compatibility.colorTargets().toArray(ColorTargetState[]::new), base.getDepthStencilState(),
                    base.getPolygonMode(), base.isCull(), base.getVertexFormatBindings().toArray(VertexFormat[]::new),
                    base.getPrimitiveTopology(), base.pushConstantSize(), base.getSortKey());
        }
    }

    private record ContextKey(Variant variant, boolean localRead,
                              VkNativePipelineCompiler.Compatibility compatibility) {
    }

    static final class PreparedContext {
        private final ContextKey key;
        private final Map<RenderPipeline, PreparedPipeline> pipelines = new IdentityHashMap<>();

        private PreparedContext(ContextKey key) {
            this.key = key;
        }
    }

    private record PreparedPipeline(VkNativeFrontendCompiler.CompiledPipeline compiled, boolean additive) {
    }

    private enum Utility {
        NONE(""), IN_SAMPLER("InSampler"), DEPTH_BOUNDS("DepthBoundsSampler");
        private final String sampler;

        Utility(String sampler) {
            this.sampler = sampler;
        }
    }
}
