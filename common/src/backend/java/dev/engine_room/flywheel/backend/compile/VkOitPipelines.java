package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.engine.BerFamily;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderCompiler;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderTransform;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.List;

import static dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.*;

/**
 * The VK twin of the GL {@code OitPipelines}: the non-uber OIT producers (layer/weather/ber/chunk), the resolve,
 * and the shared OIT pipeline-config vocabulary.
 */
public final class VkOitPipelines {
    public static final int[] FOLDED_INPUT_INDICES = {0, 1, 2, 3, 4, VK12.VK_ATTACHMENT_UNUSED};
    static final int FMT_RGBA32F = VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
    static final int FMT_RGBA16F = VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
    public static final int[] FOLDED_FORMATS = {FMT_RGBA32F, FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F};
    static final int FMT_RGBA8 = VK12.VK_FORMAT_R8G8B8A8_UNORM;
    static final int FMT_D32 = VK12.VK_FORMAT_D32_SFLOAT;
    static final int[] MLAB_NO_COLOR = new int[0];
    static final VkGraphicsPipeline.Blend[] MLAB_NO_BLEND = new VkGraphicsPipeline.Blend[0];
    private final VkGraphicsPipeline[] layerFolded = new VkGraphicsPipeline[OitMode.values().length];
    private final VkGraphicsPipeline[] weatherFolded = new VkGraphicsPipeline[OitMode.values().length];
    private final VkGraphicsPipeline[][] berFolded = new VkGraphicsPipeline[BerFamily.VALUES.length][OitMode.values().length];
    private final VkGraphicsPipeline[][] chunkFolded = new VkGraphicsPipeline[2][OitMode.values().length];
    // Exact-weather insert-OIT (OitConfig.exactFabulous); unused on the layered default.
    private final VkGraphicsPipeline[] weatherMlab = new VkGraphicsPipeline[OitInsertMode.values().length];
    private final VkGraphicsPipeline[][] berMlab = new VkGraphicsPipeline[BerFamily.VALUES.length][OitInsertMode.values().length];
    private final VkGraphicsPipeline[][] chunkMlab = new VkGraphicsPipeline[2][OitInsertMode.values().length];
    private final VkGraphicsPipeline[] mlabResolve = new VkGraphicsPipeline[OitInsertMode.values().length];
    @Nullable
    private VkGraphicsPipeline composite;
    @Nullable
    private VkGraphicsPipeline depth;

    @Nullable
    private VkGraphicsPipeline depthFolded;

    // ---- Folded OIT (VK_KHR_dynamic_rendering_local_read): the stages share ONE 6-attachment rendering instance
    // ([0]=depthBounds RGBA32F, [1-4]=coefficients RGBA16F, [5]=accumulate RGBA16F); each stage's pipeline
    // statically remaps fragment-output locations to its written attachments and reads the earlier stages' as input. ----

    VkOitPipelines() {
    }

    static VkGraphicsPipeline.Config oitProducerConfig(OitMode mode) {
        int ge = VK12.VK_COMPARE_OP_GREATER_OR_EQUAL;
        int cull = VK12.VK_CULL_MODE_BACK_BIT;
        return switch (mode) {
            case DEPTH_RANGE -> new VkGraphicsPipeline.Config(new int[]{FMT_RGBA32F},
                    new VkGraphicsPipeline.Blend[]{VkGraphicsPipeline.max()}, true, false, ge,
                    VkGraphicsPipeline.Vertex.INTERNAL, cull, FMT_D32);
            case GENERATE_COEFFICIENTS -> new VkGraphicsPipeline.Config(
                    new int[]{FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F},
                    new VkGraphicsPipeline.Blend[]{VkGraphicsPipeline.additive(), VkGraphicsPipeline.additive(), VkGraphicsPipeline.additive(), VkGraphicsPipeline.additive()},
                    true, false, ge, VkGraphicsPipeline.Vertex.INTERNAL, cull, FMT_D32);
            case EVALUATE -> new VkGraphicsPipeline.Config(new int[]{FMT_RGBA16F},
                    new VkGraphicsPipeline.Blend[]{VkGraphicsPipeline.additive()}, true, false, ge,
                    VkGraphicsPipeline.Vertex.INTERNAL, cull, FMT_D32);
            case OFF -> throw new IllegalArgumentException("OitMode.OFF is not a producer mode");
        };
    }

    public static int[] foldedLocations(@Nullable OitMode mode) {
        int u = VK12.VK_ATTACHMENT_UNUSED;
        if (mode == null) {
            return new int[]{u, u, u, u, u, u};
        }
        return switch (mode) {
            case DEPTH_RANGE -> new int[]{0, u, u, u, u, u};
            case GENERATE_COEFFICIENTS -> new int[]{u, 0, 1, 2, 3, u};
            case EVALUATE -> new int[]{u, u, u, u, u, 0};
            case OFF -> throw new IllegalArgumentException("OitMode.OFF is not a producer mode");
        };
    }

    public static VkGraphicsPipeline.Blend[] foldedBlends(@Nullable OitMode mode) {
        VkGraphicsPipeline.Blend off = VkGraphicsPipeline.noColorWrite();
        VkGraphicsPipeline.Blend[] b = {off, off, off, off, off, off};
        if (mode == null) {
            return b;
        }
        switch (mode) {
            case DEPTH_RANGE -> b[0] = VkGraphicsPipeline.max();
            case GENERATE_COEFFICIENTS -> {
                for (int i = 1; i <= 4; i++) {
                    b[i] = VkGraphicsPipeline.additive();
                }
            }
            case EVALUATE -> b[5] = VkGraphicsPipeline.additive();
            case OFF -> throw new IllegalArgumentException("OitMode.OFF is not a producer mode");
        }
        return b;
    }

    static VkGraphicsPipeline.Config foldedProducerConfig(OitMode mode) {
        return new VkGraphicsPipeline.Config(FOLDED_FORMATS, foldedBlends(mode), true, false,
                VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.INTERNAL, VK12.VK_CULL_MODE_BACK_BIT,
                FMT_D32)
                .withLocalRead(foldedLocations(mode), FOLDED_INPUT_INDICES);
    }

    /**
     * The insert storage bindings every producer/resolve pipeline carries (24/25/26, +27 for the A-buffer).
     */
    public static void mlabBindings(List<Binding> b, OitInsertMode oitMode) {
        b.add(new Binding(24, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(25, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        b.add(new Binding(26, TYPE_UNIFORM_BUFFER, STAGE_FRAGMENT));
        if (oitMode.needsCounter()) {
            b.add(new Binding(27, TYPE_STORAGE_BUFFER, STAGE_FRAGMENT));
        }
    }

    private static void foldedExtraBindings(List<Binding> b, OitMode mode) {
        if (mode != OitMode.DEPTH_RANGE) {
            b.add(new Binding(14, TYPE_INPUT_ATTACHMENT, STAGE_FRAGMENT));
            b.add(new Binding(15, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        }
        if (mode == OitMode.EVALUATE) {
            for (int i = 24; i <= 27; i++) {
                b.add(new Binding(i, TYPE_INPUT_ATTACHMENT, STAGE_FRAGMENT));
            }
        }
    }

    private static List<Binding> compositeBindings() {
        List<Binding> b = new ArrayList<>();
        b.add(new Binding(28, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(14, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        for (int i = 24; i <= 27; i++) {
            b.add(new Binding(i, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        }
        return b;
    }

    private static List<Binding> depthBindings(boolean folded) {
        int oitRead = folded ? TYPE_INPUT_ATTACHMENT : TYPE_COMBINED_IMAGE_SAMPLER;
        List<Binding> b = new ArrayList<>();
        b.add(new Binding(14, oitRead, STAGE_FRAGMENT));
        for (int i = 24; i <= 27; i++) {
            b.add(new Binding(i, oitRead, STAGE_FRAGMENT));
        }
        return b;
    }

    private static List<Binding> layerFoldedBindings(OitMode mode) {
        List<Binding> b = new ArrayList<>();
        b.add(new Binding(29, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(30, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        foldedExtraBindings(b, mode);
        return b;
    }

    private static List<Binding> weatherFoldedBindings(OitMode mode) {
        List<Binding> b = weatherBindings();
        foldedExtraBindings(b, mode);
        return b;
    }

    private static List<Binding> weatherBindings() {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, vsfs));
        return b;
    }

    private static List<Binding> berFoldedBindings(BerFamily family, OitMode mode) {
        List<Binding> b = berBindings(family);
        foldedExtraBindings(b, mode);
        return b;
    }

    private static VkGraphicsPipeline.Vertex berVertex(BerFamily family) {
        return switch (family) {
            case ENTITY, ITEM, ENTITY_EMISSIVE -> VkGraphicsPipeline.Vertex.ENTITY;
            case MOVING_BLOCK, BEAM -> VkGraphicsPipeline.Vertex.BLOCK;
        };
    }

    private static int berCullMode(BerFamily family) {
        return family.cull ? VK12.VK_CULL_MODE_BACK_BIT : VK12.VK_CULL_MODE_NONE;
    }

    private static List<Binding> berBindings(BerFamily family) {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        if (family.overlay) {
            b.add(new Binding(11, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_VERTEX));
        }
        if (family.lightmap) {
            b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_VERTEX));
        }
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, vsfs));
        if (family.lighting) {
            b.add(new Binding(19, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        }
        return b;
    }

    private static List<Binding> chunkFoldedBindings(OitMode mode) {
        List<Binding> b = chunkBindings();
        foldedExtraBindings(b, mode);
        return b;
    }

    private static List<Binding> chunkBindings() {
        List<Binding> b = new ArrayList<>();
        int vsfs = STAGE_VERTEX | STAGE_FRAGMENT;
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
        b.add(new Binding(12, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_VERTEX));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(20, TYPE_UNIFORM_BUFFER, vsfs));
        b.add(new Binding(21, TYPE_UNIFORM_BUFFER, STAGE_VERTEX));
        return b;
    }

    // The pipeline constructors don't clean up on failure; every factory's catch destroys what it already compiled before rethrowing.
    private static void destroyModules(long... modules) {
        VkDevice device = VkContext.vkDevice();
        for (long module : modules) {
            if (module != 0L) {
                VK12.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    public VkGraphicsPipeline compositePipeline() {
        if (composite == null) {
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("oit_fullscreen",
                        VkShaderTransform.toVulkan(RenderPassShaders.fullscreenVertex(),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("oit_composite",
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleOitComposite(),
                                VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(new int[]{FMT_RGBA8},
                        new VkGraphicsPipeline.Blend[]{VkGraphicsPipeline.composite()},
                        true, true, VK12.VK_COMPARE_OP_ALWAYS, VkGraphicsPipeline.Vertex.NONE, VK12.VK_CULL_MODE_NONE,
                        FMT_D32);
                layout = new VkDescriptorLayout(compositeBindings(), 0, 0);
                composite = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                throw t;
            }
        }
        return composite;
    }

    public VkGraphicsPipeline depthPipeline(boolean folded) {
        if (folded) {
            if (depthFolded == null) {
                depthFolded = buildDepth(true);
            }
            return depthFolded;
        }
        if (depth == null) {
            depth = buildDepth(false);
        }
        return depth;
    }

    private VkGraphicsPipeline buildDepth(boolean folded) {
        String fsGl = RenderPassShaders.assembleOitDepth(folded ? VkPrograms.LOCAL_READ : ShaderAssembly.NO_EXTRA);
        long vs = 0;
        long fs = 0;
        VkDescriptorLayout layout = null;
        try {
            vs = VkShaderCompiler.compileModule("oit_fullscreen_d",
                    VkShaderTransform.toVulkan(RenderPassShaders.fullscreenVertex(), VkShaderTransform.Stage.VERTEX),
                    VkShaderCompiler.KIND_VERTEX);
            fs = VkShaderCompiler.compileModule("oit_depth",
                    VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
            VkGraphicsPipeline.Config config;
            // Color masked off (the stage writes only gl_FragDepth); the standalone pass binds accumulate as a write-disabled dummy.
            if (folded) {
                config = new VkGraphicsPipeline.Config(FOLDED_FORMATS, foldedBlends(null),
                        true, true, VK12.VK_COMPARE_OP_ALWAYS, VkGraphicsPipeline.Vertex.NONE, VK12.VK_CULL_MODE_NONE,
                        FMT_D32)
                        .withLocalRead(foldedLocations(null), FOLDED_INPUT_INDICES);
            } else {
                config = new VkGraphicsPipeline.Config(new int[]{FMT_RGBA16F},
                        new VkGraphicsPipeline.Blend[]{VkGraphicsPipeline.noColorWrite()},
                        true, true, VK12.VK_COMPARE_OP_ALWAYS, VkGraphicsPipeline.Vertex.NONE, VK12.VK_CULL_MODE_NONE,
                        FMT_D32);
            }
            layout = new VkDescriptorLayout(depthBindings(folded), 0, 0);
            return new VkGraphicsPipeline(layout, vs, fs, config);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModules(vs, fs);
            throw t;
        }
    }

    public VkGraphicsPipeline layerFoldedPipeline(OitMode mode) {
        VkGraphicsPipeline p = layerFolded[mode.ordinal()];
        if (p == null) {
            String fsGl = RenderPassShaders.assembleLayerOitFragment(mode, VkPrograms.LOCAL_READ);
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("layer_fullscreen",
                        VkShaderTransform.toVulkan(RenderPassShaders.fullscreenVertex(),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("layer_oit" + mode.name,
                        VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT),
                        VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(FOLDED_FORMATS, foldedBlends(mode),
                        true, false,
                        VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.NONE, VK12.VK_CULL_MODE_NONE,
                        FMT_D32)
                        .withLocalRead(foldedLocations(mode), FOLDED_INPUT_INDICES);
                layout = new VkDescriptorLayout(layerFoldedBindings(mode), 0, 0);
                p = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                throw t;
            }
            layerFolded[mode.ordinal()] = p;
        }
        return p;
    }

    public VkGraphicsPipeline weatherFoldedPipeline(OitMode mode) {
        VkGraphicsPipeline p = weatherFolded[mode.ordinal()];
        if (p == null) {
            String fsGl = RenderPassShaders.assembleWeatherOitFragment(mode, VkPrograms.LOCAL_READ);
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("weather_oit_vertex",
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleWeatherOitVertex(),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("weather_oit" + mode.name,
                        VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT),
                        VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(FOLDED_FORMATS, foldedBlends(mode),
                        true, false,
                        VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.PARTICLE, VK12.VK_CULL_MODE_NONE,
                        FMT_D32)
                        .withLocalRead(foldedLocations(mode), FOLDED_INPUT_INDICES);
                layout = new VkDescriptorLayout(weatherFoldedBindings(mode), 0, 0);
                p = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                throw t;
            }
            weatherFolded[mode.ordinal()] = p;
        }
        return p;
    }

    public VkGraphicsPipeline berFoldedPipeline(BerFamily family, OitMode mode) {
        VkGraphicsPipeline p = berFolded[family.ordinal()][mode.ordinal()];
        if (p == null) {
            String fsGl = RenderPassShaders.assembleBerOitFragment(family, mode, VkPrograms.LOCAL_READ);
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("ber_oit" + family.suffix + "_vertex",
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleBerOitVertex(family),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("ber_oit" + family.suffix + mode.name,
                        VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT),
                        VkShaderCompiler.KIND_FRAGMENT);
                // The family's vanilla fixed function; reversed-Z LEQUAL -> GREATER_OR_EQUAL.
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(FOLDED_FORMATS, foldedBlends(mode),
                        true, false,
                        VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, berVertex(family), berCullMode(family), FMT_D32)
                        .withLocalRead(foldedLocations(mode), FOLDED_INPUT_INDICES);
                layout = new VkDescriptorLayout(berFoldedBindings(family, mode), 0, 0);
                p = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                throw t;
            }
            berFolded[family.ordinal()][mode.ordinal()] = p;
        }
        return p;
    }

    public VkGraphicsPipeline chunkFoldedPipeline(OitMode mode) {
        int lin = TerrainAtlasFilter.linear() ? 1 : 0;
        VkGraphicsPipeline p = chunkFolded[lin][mode.ordinal()];
        if (p == null) {
            String fsGl = RenderPassShaders.assembleChunkOitFragment(mode, lin == 1, VkPrograms.LOCAL_READ);
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("chunk_oit_block",
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleChunkOitVertex(),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("chunk_oit_block" + mode.name,
                        VkShaderTransform.toVulkan(fsGl, VkShaderTransform.Stage.FRAGMENT),
                        VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(FOLDED_FORMATS, foldedBlends(mode),
                        true, false,
                        VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.BLOCK,
                        VK12.VK_CULL_MODE_BACK_BIT, FMT_D32)
                        .withLocalRead(foldedLocations(mode), FOLDED_INPUT_INDICES);
                layout = new VkDescriptorLayout(chunkFoldedBindings(mode), 0, 0);
                p = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                throw t;
            }
            chunkFolded[lin][mode.ordinal()] = p;
        }
        return p;
    }

    public VkGraphicsPipeline weatherMlabPipeline(OitInsertMode oitMode) {
        if (weatherMlab[oitMode.ordinal()] == null) {
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("weather_oit_vertex",
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleWeatherOitVertex(),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("weather_mlab_" + oitMode,
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleWeatherMlabFragment(oitMode),
                                VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(MLAB_NO_COLOR, MLAB_NO_BLEND, true,
                        false,
                        VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.PARTICLE, VK12.VK_CULL_MODE_NONE,
                        FMT_D32);
                List<Binding> b = weatherBindings();
                mlabBindings(b, oitMode);
                layout = new VkDescriptorLayout(b, 0, 0);
                weatherMlab[oitMode.ordinal()] = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                throw t;
            }
        }
        return weatherMlab[oitMode.ordinal()];
    }

    public VkGraphicsPipeline berMlabPipeline(BerFamily family, OitInsertMode oitMode) {
        if (berMlab[family.ordinal()][oitMode.ordinal()] == null) {
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("ber_oit" + family.suffix + "_vertex",
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleBerOitVertex(family),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("ber_mlab" + family.suffix + "_" + oitMode,
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleBerMlabFragment(family, oitMode),
                                VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(MLAB_NO_COLOR, MLAB_NO_BLEND, true,
                        false,
                        VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, berVertex(family), berCullMode(family), FMT_D32);
                List<Binding> b = berBindings(family);
                mlabBindings(b, oitMode);
                layout = new VkDescriptorLayout(b, 0, 0);
                berMlab[family.ordinal()][oitMode.ordinal()] = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                throw t;
            }
        }
        return berMlab[family.ordinal()][oitMode.ordinal()];
    }

    public VkGraphicsPipeline chunkMlabPipeline(OitInsertMode oitMode) {
        int lin = TerrainAtlasFilter.linear() ? 1 : 0;
        VkGraphicsPipeline p = chunkMlab[lin][oitMode.ordinal()];
        if (p == null) {
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("chunk_oit_block",
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleChunkOitVertex(),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("chunk_mlab_block_" + oitMode,
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleChunkMlabFragment(oitMode, lin == 1),
                                VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(MLAB_NO_COLOR, MLAB_NO_BLEND, true,
                        false,
                        VK12.VK_COMPARE_OP_GREATER_OR_EQUAL, VkGraphicsPipeline.Vertex.BLOCK,
                        VK12.VK_CULL_MODE_BACK_BIT, FMT_D32);
                List<Binding> b = chunkBindings();
                mlabBindings(b, oitMode);
                layout = new VkDescriptorLayout(b, 0, 0);
                p = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                throw t;
            }
            chunkMlab[lin][oitMode.ordinal()] = p;
        }
        return p;
    }

    public VkGraphicsPipeline mlabResolvePipeline(OitInsertMode oitMode) {
        if (mlabResolve[oitMode.ordinal()] == null) {
            long vs = 0;
            long fs = 0;
            VkDescriptorLayout layout = null;
            try {
                vs = VkShaderCompiler.compileModule("mlab_fullscreen",
                        VkShaderTransform.toVulkan(RenderPassShaders.fullscreenVertex(),
                                VkShaderTransform.Stage.VERTEX), VkShaderCompiler.KIND_VERTEX);
                fs = VkShaderCompiler.compileModule("mlab_resolve_" + oitMode,
                        VkShaderTransform.toVulkan(RenderPassShaders.assembleMlabResolve(oitMode),
                                VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
                VkGraphicsPipeline.Config config = new VkGraphicsPipeline.Config(new int[]{FMT_RGBA8},
                        new VkGraphicsPipeline.Blend[]{VkGraphicsPipeline.premultiplied()},
                        true, true, VK12.VK_COMPARE_OP_ALWAYS, VkGraphicsPipeline.Vertex.NONE, VK12.VK_CULL_MODE_NONE,
                        FMT_D32);
                List<Binding> b = new ArrayList<>();
                mlabBindings(b, oitMode);
                b.add(new Binding(29, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                b.add(new Binding(30, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                b.add(new Binding(32, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                b.add(new Binding(33, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                b.add(new Binding(34, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                b.add(new Binding(35, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                b.add(new Binding(36, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                b.add(new Binding(37, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_FRAGMENT));
                layout = new VkDescriptorLayout(b, 0, 0);
                mlabResolve[oitMode.ordinal()] = new VkGraphicsPipeline(layout, vs, fs, config);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(vs, fs);
                throw t;
            }
        }
        return mlabResolve[oitMode.ordinal()];
    }

    void delete() {
        if (composite != null) {
            composite.delete();
            composite = null;
        }
        if (depth != null) {
            depth.delete();
            depth = null;
        }
        if (depthFolded != null) {
            depthFolded.delete();
            depthFolded = null;
        }
        for (int i = 0; i < layerFolded.length; i++) {
            if (layerFolded[i] != null) {
                layerFolded[i].delete();
                layerFolded[i] = null;
            }
            if (weatherFolded[i] != null) {
                weatherFolded[i].delete();
                weatherFolded[i] = null;
            }
        }
        for (VkGraphicsPipeline[] perFamily : berFolded) {
            for (int i = 0; i < perFamily.length; i++) {
                if (perFamily[i] != null) {
                    perFamily[i].delete();
                    perFamily[i] = null;
                }
            }
        }
        for (int lin = 0; lin < 2; lin++) {
            for (int i = 0; i < chunkFolded[lin].length; i++) {
                if (chunkFolded[lin][i] != null) {
                    chunkFolded[lin][i].delete();
                    chunkFolded[lin][i] = null;
                }
            }
            for (int m = 0; m < chunkMlab[lin].length; m++) {
                if (chunkMlab[lin][m] != null) {
                    chunkMlab[lin][m].delete();
                    chunkMlab[lin][m] = null;
                }
            }
        }
        for (int m = 0; m < mlabResolve.length; m++) {
            if (weatherMlab[m] != null) {
                weatherMlab[m].delete();
                weatherMlab[m] = null;
            }
            if (mlabResolve[m] != null) {
                mlabResolve[m].delete();
                mlabResolve[m] = null;
            }
        }
        for (VkGraphicsPipeline[] perFamily : berMlab) {
            for (int m = 0; m < perFamily.length; m++) {
                if (perFamily[m] != null) {
                    perFamily[m].delete();
                    perFamily[m] = null;
                }
            }
        }
    }
}
