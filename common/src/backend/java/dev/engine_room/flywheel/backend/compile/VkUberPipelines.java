package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.OitTransparency;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
#endif
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkBindlessTable;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderCompiler;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderTransform;
import dev.engine_room.flywheel.lib.material.StandardMaterialShaders;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.*;

/**
 * The uber instance draw pipelines (opaque, the wavelet OIT producers + folded twins, insert producers, crumbling).
 * Type/cutout/fog/embedded are RUNTIME-dispatched, so only light + material shaders + fixed-function state (+ the
 * {@link RenderPassShaders#readsEmbedded} variant) key a pipeline, per {@link ProgramGeneration}. Draws select the
 * newest built generation; the current one builds through {@link VkAsyncBuilds} from GLSL assembled on the render
 * thread.
 */
public final class VkUberPipelines {
    private final Map<UberDrawKey, ProgramVersions<VkAsyncBuilds.Build<?>>> draws = new HashMap<>();
    private final Map<UberOitKey, ProgramVersions<VkAsyncBuilds.Build<?>[]>> oits = new HashMap<>();
    private final Map<UberMlabKey, ProgramVersions<VkAsyncBuilds.Build<?>>> mlabs = new HashMap<>();
    private final Map<CrumblingKey, ProgramVersions<VkAsyncBuilds.Build<?>>> crumblings = new HashMap<>();
    private final int depthFormat;

    VkUberPipelines(int depthFormat) {
        this.depthFormat = depthFormat;
    }

    private static void destroyModules(long... modules) {
        VkDevice device = VkContext.vkDevice();
        for (long module : modules) {
            if (module != 0L) {
                VK10.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    private static List<Binding> withLineFrame(List<Binding> bindings, MaterialShaders shaders) {
        if (shaders.vertexSource().equals(StandardMaterialShaders.LINE.vertexSource()))
            bindings.add(new Binding(41, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        return bindings;
    }

    private static List<Binding> drawBindings(boolean embedded, boolean bindless, LightShader _) {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(2, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(4, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(5, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(6, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(7, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(19, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(20, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(22, TYPE_UNIFORM_BUFFER, vsfs));
        if (!bindless) {
            b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            b.add(new Binding(11, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        }
        if (embedded || bindless) {
            b.add(new Binding(23, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        }
        return b;
    }

    private static List<Binding> oitProducerBindings(OitMode mode, boolean embedded, boolean folded, boolean bindless,
                                                     LightShader _) {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(2, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(4, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(22, TYPE_UNIFORM_BUFFER, vsfs));
        int oitRead = folded ? TYPE_INPUT_ATTACHMENT : TYPE_COMBINED_IMAGE_SAMPLER;
        b.add(new Binding(5, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(6, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(19, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(20, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        if (!bindless) {
            b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            b.add(new Binding(11, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        }
        if (mode != OitMode.DEPTH_RANGE) {
            if (!bindless) {
                b.add(new Binding(15, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            }
            if (folded || !bindless) {
                b.add(new Binding(14, oitRead, STAGE_FRAGMENT));
            }
        }
        if (mode == OitMode.EVALUATE && (folded || !bindless)) {
            for (int i = 24; i <= 27; i++) {
                b.add(new Binding(i, oitRead, STAGE_FRAGMENT));
            }
        }
        if (embedded) {
            b.add(new Binding(7, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        }
        if (embedded || bindless) {
            b.add(new Binding(23, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        }
        return b;
    }

    private static List<Binding> mlabProducerBindings(boolean bindless, OitInsertMode oitMode, LightShader _) {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(2, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(4, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(5, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(6, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(19, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(20, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(22, TYPE_UNIFORM_BUFFER, vsfs));
        if (!bindless) {
            b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            b.add(new Binding(11, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
            b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        }
        b.add(new Binding(7, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(23, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        VkOitPipelines.mlabBindings(b, oitMode);
        return b;
    }

    private static List<Binding> crumblingBindings() {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(5, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(6, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(19, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(20, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(22, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(11, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(13, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        return b;
    }

    private static Consumer<Compilation> baseExtra(boolean bindless, boolean embedded, boolean drawIndex) {
        return (bindless ? VkPrograms.BINDLESS : ShaderAssembly.NO_EXTRA)
                .andThen(embedded ? RenderPassShaders.EMBEDDED : ShaderAssembly.NO_EXTRA)
                .andThen(ctx -> {
                    if (!drawIndex) {
                        ctx.define("_FLW_VK_SINGLE_DRAW");
                    }
                });
    }

    private static VkGraphicsPipeline graphicsPipeline(VkDescriptorLayout layout, long vertex, long fragment,
                                                       VkGraphicsPipeline.Config config, boolean drawIndex) {
        try {
            return new VkGraphicsPipeline(layout, vertex, fragment, config);
        } catch (ProgramAvailability.Failure failure) {
            throw failure;
        } catch (BackendUnavailableException failure) {
            if (drawIndex) {
                throw new ProgramAvailability.Failure(ProgramAvailability.Feature.DRAW_INDEX, failure);
            }
            throw failure;
        }
    }

    /**
     * Render-thread capture for a worker build: the bindless and draw-index modes, the shared bindless layout and
     * (26.3) the fused native OIT subpasses a producer runs in.
     */
    private record Capture(boolean bindless, boolean drawIndex) {
        static Capture take() {
            boolean bindless = VkPrograms.bindlessTexturesEnabled();
            if (bindless) VkBindlessTable.setLayoutHandle();
            return new Capture(bindless, VkPrograms.get().drawIndex());
        }
    }

    private static VkAsyncBuilds.Build<?> build(Supplier<VkGraphicsPipeline> create) {
        return VkAsyncBuilds.request(create, VkGraphicsPipeline::delete);
    }

    private static <K> @Nullable Selection select(Map<K, ProgramVersions<VkAsyncBuilds.Build<?>>> versions, K key,
                                                  ProgramGeneration current,
                                                  Function<? super K, VkAsyncBuilds.Build<?>> request) {
        var entry = versions.computeIfAbsent(key, _ -> new ProgramVersions<>(VkAsyncBuilds.TRACKER));
        var build = entry.select(current, key, request);
        return build == null ? null : new Selection((VkGraphicsPipeline) build.result(), entry.generation());
    }

    /**
     * A built pipeline and the generation it covers.
     */
    public record Selection(VkGraphicsPipeline pipeline, ProgramGeneration generation) {
    }

    /**
     * The newest built opaque uber pipeline of the material, null before the first; builds the current generation's.
     */
    public @Nullable Selection drawPipeline(Material material, boolean embedded, LightSmoothness smoothness,
                                            int colorFormat, int depthFormat) {
        var key = new UberDrawKey(material.shaders(), material.light(), smoothness, FrameUniforms.debugMode(),
                colorFormat, depthFormat,
                material.transparency(), material.depthTest(), material.writeMask().depth(),
                material.writeMask().color(),
                material.backfaceCulling(), material.polygonOffset(), embedded);
        return select(draws, key, ProgramGeneration.current(), this::requestDraw);
    }

    private VkAsyncBuilds.Build<?> requestDraw(UberDrawKey key) {
        Capture capture = Capture.take();
        Consumer<Compilation> extra = baseExtra(capture.bindless(), key.embedded(), capture.drawIndex());
        String vsGl = RenderPassShaders.assembleUberIndirectVertex(key.materialShaders(), key.debug() != DebugMode.OFF,
                extra);
        String fsGl = RenderPassShaders.uberFragment(key.light(), key.materialShaders(), key.smoothness(), key.debug(),
                extra);
        return build(() -> VkPrograms.bindless(capture.bindless(), () -> createDraw(key, capture, vsGl, fsGl)));
    }

    private VkGraphicsPipeline createDraw(UberDrawKey key, Capture capture, String vsGl, String fsGl) {
        boolean bindless = capture.bindless();
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule("indirect_uber_vertex",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule(
                    "flw_indirect_uber" + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug()
                                                                                              .getSerializedName()),
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            var config = VkGraphicsPipeline.material(key.colorFormat(), key.depthFormat(), key.transparency(),
                    key.depthTest(),
                    key.depthWrite(), key.colorWrite(), key.cull(), key.polygonOffset());
            layout = new VkDescriptorLayout(withLineFrame(drawBindings(true, bindless, key.light()),
                    key.materialShaders()), 0, 0, bindless);
            return graphicsPipeline(layout, vs, fs, config, capture.drawIndex());
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    /**
     * {@code (mode, folded)}'s wavelet producer of the material from the newest generation whose every producer the
     * chain draws is built (they draw together), null before the first; builds the current generation's.
     */
    public @Nullable Selection oitProducerPipeline(Material material, boolean embedded, LightSmoothness smoothness,
                                                   OitMode mode, boolean folded) {
        var key = new UberOitKey(material.shaders(), material.light(), smoothness, FrameUniforms.debugMode(),
                material.depthTest(), material.backfaceCulling(), material.polygonOffset(),
                OitTransparency.additive(material), embedded, VkPrograms.get().localRead());
        var entry = oits.computeIfAbsent(key, _ -> new ProgramVersions<>(VkAsyncBuilds.GROUP));
        var group = entry.select(ProgramGeneration.current(), key, this::requestOitProducers);
        return group == null ? null
                : new Selection((VkGraphicsPipeline) group[producerSlot(mode, folded)].result(), entry.generation());
    }

    private static int producerSlot(OitMode mode, boolean folded) {
        return mode.ordinal() * 2 + (folded ? 1 : 0);
    }

    // Mirrors ShaderWarmup.warmVk: local read folds every mode, plus unfolded split instance bounds.
    private VkAsyncBuilds.Build<?>[] requestOitProducers(UberOitKey key) {
        List<VkAsyncBuilds.Build<?>> members = new ArrayList<>();
        int[] slots = new int[OitMode.values().length * 2];
        Arrays.fill(slots, -1);
        for (OitMode mode : OitMode.values()) {
            if (mode == OitMode.OFF) continue;
            for (boolean folded : new boolean[]{false, true}) {
                boolean drawn = key.localRead() ? folded || mode == OitMode.DEPTH_RANGE
                        && OitFramebuffer.SPLIT_INSTANCE_BOUNDS : !folded;
                if (!drawn) continue;
                slots[producerSlot(mode, folded)] = members.size();
                members.add(requestOitProducer(new UberOitProducer(key, mode, folded,
                        mode == OitMode.EVALUATE && key.additive())));
            }
        }
        var group = new VkAsyncBuilds.Build<?>[slots.length];
        for (int slot = 0; slot < slots.length; slot++) {
            group[slot] = slots[slot] < 0 ? null : members.get(slots[slot]);
        }
        return group;
    }

    private VkAsyncBuilds.Build<?> requestOitProducer(UberOitProducer producer) {
        UberOitKey key = producer.key();
        Capture capture = Capture.take();
        Consumer<Compilation> extra = baseExtra(capture.bindless(), key.embedded(), capture.drawIndex());
        String vsGl = RenderPassShaders.assembleUberIndirectVertex(key.materialShaders(), key.debug() != DebugMode.OFF,
                extra);
        Consumer<Compilation> fsExtra = producer.emission() ? extra.andThen(RenderPassShaders.EMISSION_PRODUCER)
                : extra;
        String fsGl = RenderPassShaders.assembleUberOitFragment(producer.mode(), key.light(), key.materialShaders(),
                key.smoothness(), key.debug(),
                producer.folded() ? fsExtra.andThen(VkPrograms.LOCAL_READ) : fsExtra);
        #if MC >= 26.3
        List<VkGraphicsPipeline.Subpass> subpasses = producer.folded() || producer.emission()
                ? VkNativeOitPass.engineSubpasses(producer.mode(), producer.emission()) : List.of();
        #endif
        return build(() -> VkPrograms.bindless(capture.bindless(), () -> producer.folded()
                ? VkPrograms.optional(ProgramAvailability.Feature.LOCAL_READ,
                () -> createOitProducer(producer, capture, vsGl, fsGl #if MC >= 26.3 , subpasses #endif))
                : createOitProducer(producer, capture, vsGl, fsGl #if MC >= 26.3 , subpasses #endif)));
    }

    private VkGraphicsPipeline createOitProducer(UberOitProducer producer, Capture capture, String vsGl, String fsGl
                                                 #if MC >= 26.3 , List<VkGraphicsPipeline.Subpass> subpasses #endif) {
        UberOitKey key = producer.key();
        boolean bindless = capture.bindless();
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule("oit_uber_vertex",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule(
                    "oit_uber_" + producer.mode() + (producer.emission() ? "_emission" : "")
                            + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug()
                                                                                  .getSerializedName()),
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            VkGraphicsPipeline.Config base = producer.folded() ? VkOitPipelines.foldedProducerConfig(
                    producer.mode(), depthFormat) : VkOitPipelines.instanceProducerConfig(producer.mode(), depthFormat);
            var config = new VkGraphicsPipeline.Config(base.colorFormats(), base.blends(), base.depthTest(),
                    base.depthWrite(),
                    VkGraphicsPipeline.compareOp(key.depthTest()), base.vertex(),
                    key.cull() ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE, base.depthFormat(),
                    key.polygonOffset() ? 10.0F : 0.0F, key.polygonOffset() ? 1.0F : 0.0F,
                    base.attachmentLocations(), base.inputAttachmentIndices());
            layout = new VkDescriptorLayout(withLineFrame(oitProducerBindings(producer.mode(), true,
                    producer.folded(), bindless, key.light()), key.materialShaders()), 0, 0, bindless);
            VkGraphicsPipeline pipeline = graphicsPipeline(layout, vs, fs, config, capture.drawIndex());
            #if MC >= 26.3
            for (var subpass : subpasses) pipeline.subpassHandle(subpass);
            #endif
            return pipeline;
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    /**
     * The newest built insert producer of the material, null before the first; builds the current generation's.
     */
    public @Nullable Selection mlabProducerPipeline(OitInsertMode oitMode, Material material, boolean embedded,
                                                    LightSmoothness smoothness) {
        var key = new UberMlabKey(oitMode, material.shaders(), material.light(), smoothness, FrameUniforms.debugMode(),
                material.depthTest(), material.backfaceCulling(), material.polygonOffset(),
                OitTransparency.additive(material), embedded);
        return select(mlabs, key, ProgramGeneration.current(), this::requestMlabProducer);
    }

    private VkAsyncBuilds.Build<?> requestMlabProducer(UberMlabKey key) {
        Capture capture = Capture.take();
        Consumer<Compilation> extra = baseExtra(capture.bindless(), key.embedded(), capture.drawIndex());
        String vsGl = RenderPassShaders.assembleUberIndirectVertex(key.materialShaders(), key.debug() != DebugMode.OFF,
                extra);
        String fsGl = RenderPassShaders.assembleUberMlabFragment(key.oitMode(), key.light(), key.materialShaders(),
                key.smoothness(), key.debug(),
                key.emission() ? extra.andThen(RenderPassShaders.EMISSION_PRODUCER) : extra);
        #if MC >= 26.3
        List<VkGraphicsPipeline.Subpass> subpasses = VkNativeOitPass.engineSubpasses(key.oitMode());
        #endif
        return build(() -> VkPrograms.bindless(capture.bindless(),
                () -> VkPrograms.optional(ProgramAvailability.insert(key.oitMode()),
                        () -> createMlabProducer(key, capture, vsGl, fsGl #if MC >= 26.3 , subpasses #endif))));
    }

    private VkGraphicsPipeline createMlabProducer(UberMlabKey key, Capture capture, String vsGl, String fsGl
                                                  #if MC >= 26.3 , List<VkGraphicsPipeline.Subpass> subpasses #endif) {
        boolean bindless = capture.bindless();
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule("mlab_uber_vertex",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule(
                    "mlab_uber_" + key.oitMode() + (key.emission() ? "_emission" : "")
                            + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug()
                                                                                  .getSerializedName()),
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            var config = new VkGraphicsPipeline.Config(VkOitPipelines.MLAB_NO_COLOR, VkOitPipelines.MLAB_NO_BLEND, true,
                    false,
                    VkGraphicsPipeline.compareOp(key.depthTest()), VkGraphicsPipeline.Vertex.INTERNAL,
                    key.cull() ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE, depthFormat,
                    key.polygonOffset() ? 10.0F : 0.0F, key.polygonOffset() ? 1.0F : 0.0F);
            layout = new VkDescriptorLayout(withLineFrame(mlabProducerBindings(bindless, key.oitMode(), key.light()),
                    key.materialShaders()), 0, 0, bindless);
            VkGraphicsPipeline pipeline = graphicsPipeline(layout, vs, fs, config, capture.drawIndex());
            #if MC >= 26.3
            for (var subpass : subpasses) pipeline.subpassHandle(subpass);
            #endif
            return pipeline;
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    /**
     * Null until built; builds it.
     */
    public @Nullable VkGraphicsPipeline crumblingPipeline(Material crumblingMaterial, InstanceType<?> type,
                                                          LightSmoothness smoothness, int colorFormat,
                                                          int depthFormat) {
        var selection = select(crumblings, new CrumblingKey(type, smoothness, FrameUniforms.debugMode(), colorFormat,
                        depthFormat, crumblingMaterial.depthTest(), crumblingMaterial.backfaceCulling()),
                ProgramGeneration.NONE, this::requestCrumbling);
        return selection == null ? null : selection.pipeline();
    }

    private VkAsyncBuilds.Build<?> requestCrumbling(CrumblingKey key) {
        String vsGl = RenderPassShaders.assembleIndirectCrumblingVertex(key.instanceType(),
                key.debug() != DebugMode.OFF);
        String fsGl = RenderPassShaders.crumblingFragment(true, key.smoothness(), key.debug());
        return build(() -> createCrumbling(key, vsGl, fsGl));
    }

    private VkGraphicsPipeline createCrumbling(CrumblingKey key, String vsGl, String fsGl) {
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule("crumbling_vertex",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule("crumbling_frag",
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            var config = new VkGraphicsPipeline.Config(new int[]{key.colorFormat()},
                    new VkGraphicsPipeline.Blend[]{VkGraphicsPipeline.crumbling()},
                    true, false, VkGraphicsPipeline.compareOp(key.depthTest()), VkGraphicsPipeline.Vertex.INTERNAL,
                    key.cull() ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE, key.depthFormat(), 10.0F, 1.0F);
            layout = new VkDescriptorLayout(crumblingBindings(), 0, 0);
            return new VkGraphicsPipeline(layout, vs, fs, config);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    /**
     * After {@link VkAsyncBuilds#join}.
     */
    void delete() {
        draws.values().forEach(ProgramVersions::release);
        draws.clear();
        oits.values().forEach(ProgramVersions::release);
        oits.clear();
        mlabs.values().forEach(ProgramVersions::release);
        mlabs.clear();
        crumblings.values().forEach(ProgramVersions::release);
        crumblings.clear();
    }

    private record UberDrawKey(MaterialShaders materialShaders, LightShader light, LightSmoothness smoothness,
                               DebugMode debug, int colorFormat, int depthFormat, Transparency transparency,
                               DepthTest depthTest,
                               boolean depthWrite, boolean colorWrite, boolean cull, boolean polygonOffset,
                               boolean embedded) {
    }

    private record UberOitKey(MaterialShaders materialShaders, LightShader light, LightSmoothness smoothness,
                              DebugMode debug, DepthTest depthTest, boolean cull, boolean polygonOffset,
                              boolean additive, boolean embedded, boolean localRead) {
    }

    private record UberOitProducer(UberOitKey key, OitMode mode, boolean folded, boolean emission) {
    }

    private record UberMlabKey(OitInsertMode oitMode, MaterialShaders materialShaders, LightShader light,
                               LightSmoothness smoothness,
                               DebugMode debug, DepthTest depthTest, boolean cull, boolean polygonOffset,
                               boolean emission, boolean embedded) {
    }

    private record CrumblingKey(InstanceType<?> instanceType, LightSmoothness smoothness, DebugMode debug,
                                int colorFormat, int depthFormat, DepthTest depthTest, boolean cull) {
    }
}
