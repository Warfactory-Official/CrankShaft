package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainOverlayDraw;

import dev.engine_room.flywheel.backend.engine.terrain.TerrainPipelines;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderCompiler;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderTransform;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.*;

public final class VkTerrainPrograms {
    private static final Identifier CULL = ResourceUtil.rl("internal/indirect/terrain_cull.comp");
    private static final Identifier TRANSLUCENT_OIT_CULL = ResourceUtil.rl(
            "internal/indirect/terrain_translucent_oit_cull.comp");

    private static final Consumer<Compilation> TRANSLUCENT_INSTANCED = ctx -> {
        ctx.define("_FLW_TRANSLUCENT_INSTANCED");
        ctx.requireExtension("GL_EXT_buffer_reference2");
        ctx.requireExtension("GL_EXT_buffer_reference_uvec2");
    };

    private static final Consumer<Compilation> VK_BDA = ctx -> {
        ctx.define("_FLW_VK_BDA");
        ctx.requireExtension("GL_EXT_buffer_reference2");
        ctx.requireExtension("GL_EXT_buffer_reference_uvec2");
    };

    private final ShaderSources sources;
    private final VkGraphicsPipeline[] solid = new VkGraphicsPipeline[2];
    private final VkGraphicsPipeline[] cutout = new VkGraphicsPipeline[2];
    private final Map<OverlayKey, VkGraphicsPipeline> overlays = new HashMap<>();
    private final Map<TranslucentOverlayKey, VkGraphicsPipeline> translucentOverlays = new HashMap<>();
    private final VkGraphicsPipeline[][] translucentProducer = new VkGraphicsPipeline[2][OitMode.values().length];
    private final VkGraphicsPipeline[][] translucentProducerFolded = new VkGraphicsPipeline[2][OitMode.values().length];
    private final VkGraphicsPipeline[][] translucentMlab = new VkGraphicsPipeline[2][OitInsertMode.values().length];
    private final VkGraphicsPipeline[][] classicTranslucentProducer = new VkGraphicsPipeline[2][OitMode.values().length];
    private final VkGraphicsPipeline[][] classicTranslucentProducerFolded = new VkGraphicsPipeline[2][OitMode.values().length];
    private final VkGraphicsPipeline[][] classicTranslucentMlab = new VkGraphicsPipeline[2][OitInsertMode.values().length];
    private final VkComputePipeline[] cull = new VkComputePipeline[2];
    private final VkComputePipeline[] translucentOitCull = new VkComputePipeline[2];
    private final int depthFormat;
    VkTerrainPrograms(ShaderSources sources, int depthFormat) {
        this.sources = sources;
        this.depthFormat = depthFormat;
    }

    private static List<Binding> cullBindings() {
        List<Binding> b = new ArrayList<>();
        for (int i = 0; i <= 7; i++) {
            b.add(new Binding(i, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        }
        b.add(new Binding(8, TYPE_UNIFORM_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(9, TYPE_UNIFORM_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_COMPUTE));
        b.add(new Binding(11, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        return b;
    }

    private static List<Binding> translucentOitCullBindings() {
        List<Binding> b = new ArrayList<>();
        b.add(new Binding(0, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(4, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(5, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(6, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(8, TYPE_UNIFORM_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(9, TYPE_UNIFORM_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_COMPUTE));
        return b;
    }

    private static List<Binding> drawBindings() {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(2, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(20, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        return b;
    }

    private static List<Binding> translucentProducerBindings(OitMode mode, boolean folded) {
        List<Binding> b = translucentBaseBindings();
        int oitRead = folded ? TYPE_INPUT_ATTACHMENT : TYPE_COMBINED_IMAGE_SAMPLER;
        if (mode != OitMode.DEPTH_RANGE) {
            b.add(new Binding(14, oitRead, STAGE_FRAGMENT));
            b.add(new Binding(15, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        }
        if (mode == OitMode.EVALUATE) {
            for (int i = 24; i <= 27; i++) {
                b.add(new Binding(i, oitRead, STAGE_FRAGMENT));
            }
        }
        return b;
    }

    private static List<Binding> translucentBaseBindings() {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_VERTEX));
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(20, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        return b;
    }

    private static void destroyModules(long... modules) {
        VkDevice device = VkContext.vkDevice();
        for (long module : modules) {
            if (module != 0L) {
                VK10.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    private static List<Binding> classicBindings(List<Binding> bindings) {
        bindings.removeIf(binding -> binding.binding() == 1);
        bindings.add(new Binding(23, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        return bindings;
    }

    private static void addOverlayBindings(List<Binding> bindings, List<Identifier> overlays, boolean classic) {
        TerrainOverlayPipelines.vulkanLayouts(bindings, overlays, STAGE_VERTEX);
        if (classic && !overlays.isEmpty()) {
            bindings.add(new Binding(TerrainOverlayPipelines.RECEIVER_BINDING,
                    VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER, STAGE_VERTEX));
        }
    }

    private static void deleteComputePipelines(VkComputePipeline[] pipelines) {
        for (var i = 0; i < pipelines.length; i++) {
            if (pipelines[i] != null) {
                pipelines[i].delete();
                pipelines[i] = null;
            }
        }
    }

    public VkComputePipeline cullPipeline() {
        return cullPipeline(false);
    }

    public VkComputePipeline cullPipeline(boolean receiverData) {
        var index = receiverData ? 1 : 0;
        if (cull[index] == null) {
            cull[index] = VkPrograms.optional(ProgramAvailability.Feature.OPAQUE_TERRAIN,
                    () -> buildCompute("terrain/cull", CULL, cullBindings(), receiverData));
        }
        return cull[index];
    }

    public VkComputePipeline translucentOitCullPipeline() {
        return translucentOitCullPipeline(false);
    }

    public VkComputePipeline translucentOitCullPipeline(boolean receiverData) {
        var index = receiverData ? 1 : 0;
        if (translucentOitCull[index] == null) {
            translucentOitCull[index] = VkPrograms.optional(ProgramAvailability.Feature.TERRAIN_OIT,
                    () -> buildCompute("terrain/translucent_oit_cull", TRANSLUCENT_OIT_CULL,
                            translucentOitCullBindings(), receiverData));
        }
        return translucentOitCull[index];
    }

    private VkComputePipeline buildCompute(String name, Identifier source, List<Binding> bindings,
                                           boolean receiverData) {
        var defines = receiverData ? new String[]{"_FLW_TERRAIN_RECEIVER_DATA"} : new String[0];
        var module = VkPrograms.compileCompute(receiverData ? name + "_receivers" : name,
                List.of(sources.get(source)), defines);
        VkDescriptorLayout layout = null;
        try {
            layout = new VkDescriptorLayout(bindings, 0, 0);
            return new VkComputePipeline(layout, module);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(module);
            throw t;
        }
    }

    public VkGraphicsPipeline drawPipeline(boolean cutout, int colorFormat) {
        int lin = TerrainAtlasFilter.linear() ? 1 : 0;
        VkGraphicsPipeline[] cache = cutout ? this.cutout : solid;
        if (cache[lin] == null) {
            cache[lin] = VkPrograms.optional(ProgramAvailability.Feature.OPAQUE_TERRAIN,
                    () -> buildDraw(cutout, lin == 1, colorFormat));
        }
        return cache[lin];
    }

    private VkGraphicsPipeline buildDraw(boolean cutout, boolean linear, int colorFormat) {
        return buildDraw(cutout, linear, colorFormat, List.of());
    }

    public VkGraphicsPipeline overlayPipeline(boolean cutout, int colorFormat, List<Identifier> overlay) {
        boolean linear = TerrainAtlasFilter.linear();
        return overlays.computeIfAbsent(new OverlayKey(cutout, linear, colorFormat, overlay),
                _ -> buildDraw(cutout, linear, colorFormat, overlay));
    }

    private VkGraphicsPipeline buildDraw(boolean cutout, boolean linear, int colorFormat, List<Identifier> overlay) {
        String vsGl = overlay.isEmpty() ? TerrainPipelines.assembleVertex(VkPrograms.VK.andThen(VK_BDA))
                : TerrainPipelines.assembleOverlayVertex(VkPrograms.VK.andThen(VK_BDA));
        String fsGl = overlay.isEmpty() ? TerrainPipelines.assembleFragment(cutout, linear)
                : TerrainPipelines.assembleOverlay(cutout, linear, overlay);
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule("terrain_vertex",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule(cutout ? "terrain_cutout" : "terrain_solid",
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            var blend = new VkGraphicsPipeline.Blend(false, 0, 0, 0, 0, 0, 0, VkGraphicsPipeline.COLOR_WRITE_RGBA);
            VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(new int[]{colorFormat},
                    new VkGraphicsPipeline.Blend[]{blend},
                    true, true, VK10.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.NONE,
                    VK10.VK_CULL_MODE_BACK_BIT, depthFormat);
            var bindings = drawBindings();
            TerrainOverlayPipelines.vulkanLayouts(bindings, overlay, STAGE_VERTEX);
            layout = new VkDescriptorLayout(bindings, 0, 0);
            return new VkGraphicsPipeline(layout, vs, fs, config);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    public VkGraphicsPipeline translucentProducerPipeline(OitMode mode, boolean folded) {
        return translucent(false, mode, null, folded);
    }

    public VkGraphicsPipeline classicTranslucentProducerPipeline(OitMode mode, boolean folded) {
        return translucent(true, mode, null, folded);
    }

    private VkGraphicsPipeline translucent(boolean classic, OitMode mode, @Nullable OitInsertMode insert,
                                           boolean folded) {
        var sources = TerrainOverlayDraw.sources();
        var linear = TerrainAtlasFilter.linear();
        if (!sources.isEmpty()) {
            var key = new TranslucentOverlayKey(classic, mode, insert, folded, linear, sources);
            return translucentOverlays.computeIfAbsent(key, this::optionalTranslucent);
        }
        var cache = translucentCache(classic, insert != null, folded, linear);
        var index = insert == null ? mode.ordinal() : insert.ordinal();
        if (cache[index] == null) {
            cache[index] = optionalTranslucent(
                    new TranslucentOverlayKey(classic, mode, insert, folded, linear, sources));
        }
        return cache[index];
    }

    private VkGraphicsPipeline[] translucentCache(boolean classic, boolean insert, boolean folded, boolean linear) {
        VkGraphicsPipeline[][] cache;
        if (insert) {
            cache = classic ? classicTranslucentMlab : translucentMlab;
        } else if (classic) {
            cache = folded ? classicTranslucentProducerFolded : classicTranslucentProducer;
        } else {
            cache = folded ? translucentProducerFolded : translucentProducer;
        }
        return cache[linear ? 1 : 0];
    }

    private VkGraphicsPipeline optionalTranslucent(TranslucentOverlayKey key) {
        Supplier<VkGraphicsPipeline> compile = () -> key.insert() == null
                ? buildTranslucentProducer(key.mode(), key.folded(), key.linear(), key.classic(), key.sources())
                : buildTranslucentMlab(key.insert(), key.linear(), key.classic(), key.sources());
        var available = compile;
        if (key.insert() != null) {
            available = () -> VkPrograms.optional(ProgramAvailability.insert(key.insert()), compile);
        } else if (key.folded()) {
            available = () -> VkPrograms.optional(ProgramAvailability.Feature.LOCAL_READ, compile);
        }
        return key.classic() ? available.get() : VkPrograms.optional(ProgramAvailability.Feature.TERRAIN_OIT,
                available);
    }

    private VkGraphicsPipeline buildTranslucentProducer(OitMode mode, boolean folded, boolean linear, boolean classic,
                                                        List<Identifier> overlays) {
        var vsGl = RenderPassShaders.assembleSodiumChunkOitVertex(false, false,
                classic ? VkPrograms.VK : VkPrograms.VK.andThen(TRANSLUCENT_INSTANCED), overlays);
        var fsGl = RenderPassShaders.assembleChunkOitFragment(mode, linear,
                folded ? VkPrograms.LOCAL_READ : ShaderAssembly.NO_EXTRA, overlays);
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule(classic ? "chunk_oit_sodium_classic" : "chunk_oit_sodium",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule("chunk_oit" + mode.name,
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            VkGraphicsPipeline.Config base = folded ? VkOitPipelines.foldedProducerConfig(
                    mode, depthFormat) : VkOitPipelines.oitProducerConfig(mode, depthFormat);
            VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(base.colorFormats(), base.blends(),
                    base.depthTest(), base.depthWrite(),
                    base.depthCompareOp(), classic ? VkGraphicsPipeline.Vertex.COMPACT_CHUNK
                    : VkGraphicsPipeline.Vertex.NONE, base.cullMode(), base.depthFormat(),
                    0.0F, 0.0F, base.attachmentLocations(), base.inputAttachmentIndices());
            List<Binding> bindings = translucentProducerBindings(mode, folded);
            addOverlayBindings(bindings, overlays, classic);
            layout = new VkDescriptorLayout(classic ? classicBindings(bindings) : bindings, 0, 0);
            return new VkGraphicsPipeline(layout, vs, fs, config);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    public VkGraphicsPipeline translucentMlabPipeline(OitInsertMode oitMode) {
        return translucent(false, OitMode.OFF, oitMode, false);
    }

    public VkGraphicsPipeline classicTranslucentMlabPipeline(OitInsertMode oitMode) {
        return translucent(true, OitMode.OFF, oitMode, false);
    }

    private VkGraphicsPipeline buildTranslucentMlab(OitInsertMode oitMode, boolean linear, boolean classic,
                                                    List<Identifier> overlays) {
        var vsGl = RenderPassShaders.assembleSodiumChunkOitVertex(false, false,
                classic ? VkPrograms.VK : VkPrograms.VK.andThen(TRANSLUCENT_INSTANCED), overlays);
        var fsGl = RenderPassShaders.assembleChunkMlabFragment(oitMode, linear, ShaderAssembly.NO_EXTRA, overlays);
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule(classic ? "chunk_mlab_sodium_classic" : "chunk_mlab_sodium",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule("chunk_mlab_" + oitMode,
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(VkOitPipelines.MLAB_NO_COLOR,
                    VkOitPipelines.MLAB_NO_BLEND, true, false, VK10.VK_COMPARE_OP_GREATER_OR_EQUAL,
                    classic ? VkGraphicsPipeline.Vertex.COMPACT_CHUNK : VkGraphicsPipeline.Vertex.NONE,
                    VK10.VK_CULL_MODE_BACK_BIT, depthFormat);
            List<Binding> bindings = translucentBaseBindings();
            VkOitPipelines.mlabBindings(bindings, oitMode);
            addOverlayBindings(bindings, overlays, classic);
            layout = new VkDescriptorLayout(classic ? classicBindings(bindings) : bindings, 0, 0);
            return new VkGraphicsPipeline(layout, vs, fs, config);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    void delete() {
        translucentOverlays.values().forEach(VkGraphicsPipeline::delete);
        translucentOverlays.clear();
        overlays.values().forEach(VkGraphicsPipeline::delete);
        overlays.clear();
        deleteComputePipelines(cull);
        deleteComputePipelines(translucentOitCull);
        for (int lin = 0; lin < 2; lin++) {
            if (solid[lin] != null) {
                solid[lin].delete();
                solid[lin] = null;
            }
            if (cutout[lin] != null) {
                cutout[lin].delete();
                cutout[lin] = null;
            }
            for (int i = 0; i < translucentProducer[lin].length; i++) {
                if (translucentProducer[lin][i] != null) {
                    translucentProducer[lin][i].delete();
                    translucentProducer[lin][i] = null;
                }
                if (translucentProducerFolded[lin][i] != null) {
                    translucentProducerFolded[lin][i].delete();
                    translucentProducerFolded[lin][i] = null;
                }
                if (classicTranslucentProducer[lin][i] != null) {
                    classicTranslucentProducer[lin][i].delete();
                    classicTranslucentProducer[lin][i] = null;
                }
                if (classicTranslucentProducerFolded[lin][i] != null) {
                    classicTranslucentProducerFolded[lin][i].delete();
                    classicTranslucentProducerFolded[lin][i] = null;
                }
            }
            for (int m = 0; m < translucentMlab[lin].length; m++) {
                if (translucentMlab[lin][m] != null) {
                    translucentMlab[lin][m].delete();
                    translucentMlab[lin][m] = null;
                }
                if (classicTranslucentMlab[lin][m] != null) {
                    classicTranslucentMlab[lin][m].delete();
                    classicTranslucentMlab[lin][m] = null;
                }
            }
        }
    }

    private record TranslucentOverlayKey(boolean classic, OitMode mode, @Nullable OitInsertMode insert,
                                         boolean folded, boolean linear, List<Identifier> sources) {
    }

    private record OverlayKey(boolean cutout, boolean linear, int colorFormat, List<Identifier> sources) {
    }
}
