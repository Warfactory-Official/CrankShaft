package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
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
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
    private final VkGraphicsPipeline[][] translucentProducer = new VkGraphicsPipeline[2][OitMode.values().length];
    private final VkGraphicsPipeline[][] translucentProducerFolded = new VkGraphicsPipeline[2][OitMode.values().length];
    private final VkGraphicsPipeline[][] translucentMlab = new VkGraphicsPipeline[2][OitInsertMode.values().length];
    private final VkGraphicsPipeline[][] classicTranslucentProducer = new VkGraphicsPipeline[2][OitMode.values().length];
    private final VkGraphicsPipeline[][] classicTranslucentProducerFolded = new VkGraphicsPipeline[2][OitMode.values().length];
    private final VkGraphicsPipeline[][] classicTranslucentMlab = new VkGraphicsPipeline[2][OitInsertMode.values().length];
    @Nullable
    private VkComputePipeline cull;
    @Nullable
    private VkComputePipeline translucentOitCull;
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
                VK12.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    public VkComputePipeline cullPipeline() {
        if (cull == null) {
            cull = VkPrograms.optional(ProgramAvailability.Feature.OPAQUE_TERRAIN,
                    () -> buildCompute("terrain/cull", CULL, cullBindings()));
        }
        return cull;
    }

    public VkComputePipeline translucentOitCullPipeline() {
        if (translucentOitCull == null) {
            translucentOitCull = VkPrograms.optional(ProgramAvailability.Feature.TERRAIN_OIT,
                    () -> buildCompute("terrain/translucent_oit_cull", TRANSLUCENT_OIT_CULL,
                            translucentOitCullBindings()));
        }
        return translucentOitCull;
    }

    private VkComputePipeline buildCompute(String name, Identifier source, List<Binding> bindings) {
        long module = VkPrograms.compileCompute(name, List.of(sources.get(source)));
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
        String vsGl = TerrainPipelines.assembleVertex(VkPrograms.VK.andThen(VK_BDA));
        String fsGl = TerrainPipelines.assembleFragment(cutout, linear);
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
                    true, true, VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.NONE,
                    VK12.VK_CULL_MODE_BACK_BIT, depthFormat);
            layout = new VkDescriptorLayout(drawBindings(), 0, 0);
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
        int lin = TerrainAtlasFilter.linear() ? 1 : 0;
        VkGraphicsPipeline[] cache = folded ? translucentProducerFolded[lin] : translucentProducer[lin];
        VkGraphicsPipeline p = cache[mode.ordinal()];
        if (p == null) {
            p = VkPrograms.optional(ProgramAvailability.Feature.TERRAIN_OIT,
                    () -> folded
                            ? VkPrograms.optional(ProgramAvailability.Feature.LOCAL_READ,
                                    () -> buildTranslucentProducer(mode, true, lin == 1, false))
                            : buildTranslucentProducer(mode, false, lin == 1, false));
            cache[mode.ordinal()] = p;
        }
        return p;
    }

    public VkGraphicsPipeline classicTranslucentProducerPipeline(OitMode mode, boolean folded) {
        int lin = TerrainAtlasFilter.linear() ? 1 : 0;
        VkGraphicsPipeline[] cache = (folded ? classicTranslucentProducerFolded : classicTranslucentProducer)[lin];
        VkGraphicsPipeline p = cache[mode.ordinal()];
        if (p == null) {
            p = folded ? VkPrograms.optional(ProgramAvailability.Feature.LOCAL_READ,
                    () -> buildTranslucentProducer(mode, true, lin == 1, true))
                    : buildTranslucentProducer(mode, false, lin == 1, true);
            cache[mode.ordinal()] = p;
        }
        return p;
    }

    private static List<Binding> classicBindings(List<Binding> bindings) {
        bindings.removeIf(binding -> binding.binding() == 1);
        bindings.add(new Binding(23, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        return bindings;
    }

    private VkGraphicsPipeline buildTranslucentProducer(OitMode mode, boolean folded, boolean linear, boolean classic) {
        String vsGl = RenderPassShaders.assembleSodiumChunkOitVertex(false, false,
                classic ? VkPrograms.VK : VkPrograms.VK.andThen(TRANSLUCENT_INSTANCED));
        String fsGl = RenderPassShaders.assembleChunkOitFragment(mode, linear,
                folded ? VkPrograms.LOCAL_READ : ShaderAssembly.NO_EXTRA);
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
        int lin = TerrainAtlasFilter.linear() ? 1 : 0;
        VkGraphicsPipeline p = translucentMlab[lin][oitMode.ordinal()];
        if (p == null) {
            p = VkPrograms.optional(ProgramAvailability.Feature.TERRAIN_OIT,
                    () -> VkPrograms.optional(ProgramAvailability.insert(oitMode),
                            () -> buildTranslucentMlab(oitMode, lin == 1, false)));
            translucentMlab[lin][oitMode.ordinal()] = p;
        }
        return p;
    }

    public VkGraphicsPipeline classicTranslucentMlabPipeline(OitInsertMode oitMode) {
        int lin = TerrainAtlasFilter.linear() ? 1 : 0;
        VkGraphicsPipeline p = classicTranslucentMlab[lin][oitMode.ordinal()];
        if (p == null) {
            p = VkPrograms.optional(ProgramAvailability.insert(oitMode),
                    () -> buildTranslucentMlab(oitMode, lin == 1, true));
            classicTranslucentMlab[lin][oitMode.ordinal()] = p;
        }
        return p;
    }

    private VkGraphicsPipeline buildTranslucentMlab(OitInsertMode oitMode, boolean linear, boolean classic) {
        String vsGl = RenderPassShaders.assembleSodiumChunkOitVertex(false, false,
                classic ? VkPrograms.VK : VkPrograms.VK.andThen(TRANSLUCENT_INSTANCED));
        String fsGl = RenderPassShaders.assembleChunkMlabFragment(oitMode, linear);
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule(classic ? "chunk_mlab_sodium_classic" : "chunk_mlab_sodium",
                    VkShaderTransform.toVulkan(vsGl, VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule("chunk_mlab_" + oitMode,
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(VkOitPipelines.MLAB_NO_COLOR,
                    VkOitPipelines.MLAB_NO_BLEND, true, false, VK12.VK_COMPARE_OP_GREATER_OR_EQUAL,
                    classic ? VkGraphicsPipeline.Vertex.COMPACT_CHUNK : VkGraphicsPipeline.Vertex.NONE,
                    VK12.VK_CULL_MODE_BACK_BIT, depthFormat);
            List<Binding> bindings = translucentBaseBindings();
            VkOitPipelines.mlabBindings(bindings, oitMode);
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
        for (VkComputePipeline p : new VkComputePipeline[]{cull, translucentOitCull}) {
            if (p != null) {
                p.delete();
            }
        }
        cull = null;
        translucentOitCull = null;
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
}
