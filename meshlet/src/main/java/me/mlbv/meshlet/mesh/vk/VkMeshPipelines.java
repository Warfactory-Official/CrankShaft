// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock

package me.mlbv.meshlet.mesh.vk;

import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.TerrainMode;
import dev.engine_room.flywheel.backend.compile.*;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainOverlayDraw;

import java.util.HashMap;
import java.util.Map;
#if MC >= 26.3
import dev.engine_room.flywheel.backend.engine.indirect.VkNativeOitPass;
#endif
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainPipelines;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.Binding;
import dev.engine_room.flywheel.backend.vk.shader.*;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline.Blend;
import me.mlbv.meshlet.mesh.shared.MeshShaderPrep;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Compiles + owns the pipelines of the vk_mesh_shader terrain tier (task+mesh+frag DRAW + EMIT compute),
 * lazy-compiled at first draw. The VK twin of {@code GlMeshPipelines}; layout/pipeline infrastructure is
 * CrankShaft's -- only the binding TABLES (the tier's shader ABI) live here.
 */
public final class VkMeshPipelines {
    #if MC >= 26.3
    /**
     * Render-thread loading/configuration hook for this live owner's selected terrain producers and prerequisites.
     */
    public void warmNative(VkNativeOitPass pass,
                           @Nullable OitInsertMode insert) {
        VkPrograms programs = VkPrograms.get();
        TerrainMode terrain = BackendConfig.INSTANCE.terrainMode();
        // Per-world PSO caches are distinct from the module's temporary loading warmup owner.
        if (terrain.ownsOpaque() && programs.usesGpuTerrain()) {
            emitPipeline();
            drawPipeline(false, VK12.VK_FORMAT_R8G8B8A8_UNORM);
            drawPipeline(true, VK12.VK_FORMAT_R8G8B8A8_UNORM);
        }
        if (!terrain.compositesTranslucent() || !programs.usesGpuTranslucentTerrain()) return;
        translucentEmitPipeline();
        translucentGatherPipeline();
        for (int i = 0; i < pass.phaseCount(); i++) {
            var phase = pass.phase(i);
            switch (phase) {
                case DEPTH_TERRAIN -> translucentDrawPipeline(OitMode.DEPTH_RANGE, true)
                        .subpassHandle(pass.engineCompatibility(i));
                case COEFFICIENT_TERRAIN, EVALUATE_TERRAIN -> {
                    if (insert == null) translucentDrawPipeline(phase.mode(), true)
                            .subpassHandle(pass.engineCompatibility(i));
                }
                case INSERT_TERRAIN -> translucentMlabPipeline(insert).subpassHandle(pass.engineCompatibility(i));
                default -> {
                }
            }
        }
    }
    #endif
    private static final int FMT_RGBA16F = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

    private static final int TASK = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
    private static final int MESH = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
    private static final int FRAG = VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
    private static final int COMP = VK10.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int SSBO = VkDescriptorLayout.TYPE_STORAGE_BUFFER;
    private static final int UBO = VkDescriptorLayout.TYPE_UNIFORM_BUFFER;
    private static final int SAMPLER = VkDescriptorLayout.TYPE_COMBINED_IMAGE_SAMPLER;

    // DRAW (task+mesh+frag): binding numbers mirror MDI's VkShaderTransform ABI exactly -- Sampler0 atlas @10 (frag),
    // Sampler2 lightmap @12 (mesh bakes vertexColor), Projection @16 + Globals @20 + ChunkSection(ModelViewMat) @21
    // (the SAME vanilla buffers terrain_solid.vsh binds, for the bit-exact MDI-matching transform), Fog @18 (frag).
    private static final List<Binding> DRAW_BINDINGS = List.of(
            new Binding(0, SSBO, TASK | MESH), new Binding(1, SSBO, TASK), new Binding(2, SSBO, TASK),
            new Binding(3, SSBO, TASK), new Binding(4, SSBO, TASK),
            new Binding(5, UBO, TASK | MESH), new Binding(10, SAMPLER, FRAG), new Binding(12, SAMPLER, MESH),
            new Binding(16, UBO, MESH), new Binding(18, UBO, FRAG), new Binding(20, UBO, MESH | FRAG),
            new Binding(21, UBO, MESH));

    private static final List<Binding> EMIT_BINDINGS = List.of(
            new Binding(0, SSBO, COMP), new Binding(1, SSBO, COMP), new Binding(2, SSBO, COMP),
            new Binding(3, SSBO, COMP), new Binding(4, SSBO, COMP),
            new Binding(5, UBO, COMP), new Binding(6, UBO, COMP), new Binding(7, SSBO, COMP),
            new Binding(8, SSBO, COMP));
    // Translucent GATHER (decode-once compute): regionInput@0, sectionData@1, compactSections@2, geoAddrTable@3,
    // translucentVis@4, HiZ UBO@5, meshCommands@8, lightmap@12; writes cache@16, per-quad fade@17, draw command@18.
    private static final List<Binding> TRANSLUCENT_GATHER_BINDINGS = List.of(
            new Binding(0, SSBO, COMP), new Binding(1, SSBO, COMP), new Binding(2, SSBO, COMP),
            new Binding(3, SSBO, COMP), new Binding(4, SSBO, COMP), new Binding(5, UBO, COMP),
            new Binding(8, SSBO, COMP), new Binding(12, SAMPLER, COMP),
            new Binding(16, SSBO, COMP), new Binding(17, SSBO, COMP), new Binding(18, SSBO, COMP));
    private static final List<Binding> TRANSLUCENT_EMIT_BINDINGS = List.of(
            new Binding(0, SSBO, COMP), new Binding(1, SSBO, COMP), new Binding(7, SSBO, COMP),
            new Binding(8, SSBO, COMP), new Binding(14, SSBO, COMP),
            new Binding(5, UBO, COMP), new Binding(10, SAMPLER, COMP));
    private final int depthFormat = Objects.requireNonNull(VkPrograms.get()).depthFormat();
    private final VkMeshPipeline[][] drawPipelines = new VkMeshPipeline[2][2];
    private final Map<OverlayKey, VkMeshPipeline> overlayPipelines = new HashMap<>();
    private List<Identifier> activeOpaqueSources = List.of();
    private final VkMeshPipeline[] activeOpaqueDraw = new VkMeshPipeline[2];
    private final VkMeshPipeline[][] translucentDraw = new VkMeshPipeline[2][OitMode.values().length];
    private final VkMeshPipeline[][] translucentDrawFolded = new VkMeshPipeline[2][OitMode.values().length];
    private final VkMeshPipeline[][] translucentMlab = new VkMeshPipeline[2][OitInsertMode.values().length];
    private final Map<TranslucentKey, VkMeshPipeline> translucentOverlays = new HashMap<>();
    private boolean activeOpaqueLinear;
    private int activeOpaqueColorFormat;
    private VkComputePipeline emitPipeline;
    private VkComputePipeline translucentEmit;
    private VkComputePipeline translucentGather;
    private VkComputePipeline translucentGatherOverlay;
    private List<Identifier> activeTranslucentSources = List.of();
    private boolean activeTranslucentReceiverData;
    private final VkMeshPipeline[][] activeTranslucentDraw = new VkMeshPipeline[2][OitMode.values().length];
    private final VkMeshPipeline[][] activeTranslucentDrawFolded = new VkMeshPipeline[2][OitMode.values().length];
    private final VkMeshPipeline[][] activeTranslucentMlab = new VkMeshPipeline[2][OitInsertMode.values().length];

    private static List<Binding> translucentPullDrawBindings(boolean localRead) {
        int oitRead = localRead ? VkDescriptorLayout.TYPE_INPUT_ATTACHMENT : SAMPLER;
        return List.of(
                new Binding(2, SSBO, MESH), new Binding(3, SSBO, MESH), new Binding(4, SSBO, MESH),
                new Binding(10, SAMPLER, FRAG),
                new Binding(14, oitRead, FRAG), new Binding(15, SAMPLER, FRAG),
                new Binding(16, UBO, MESH), new Binding(18, UBO, FRAG), new Binding(20, UBO, FRAG),
                new Binding(21, UBO, MESH),
                new Binding(24, oitRead, FRAG), new Binding(25, oitRead, FRAG), new Binding(26, oitRead, FRAG),
                new Binding(27, oitRead, FRAG));
    }

    private static long compileTerrainFrag(boolean cutout, boolean linear) {
        String fsVk = VkShaderTransform.toVulkan(TerrainPipelines.assembleFragment(cutout, linear, ctx -> {
            ctx.requireExtension("GL_EXT_mesh_shader");
            ctx.define("_FLW_MESH_PER_PRIMITIVE");
        }), VkShaderTransform.Stage.FRAGMENT);
        return VkShaderCompiler.compileModule(cutout ? "terrain_cutout" : "terrain_solid", fsVk,
                VkShaderCompiler.KIND_FRAGMENT);
    }

    private static List<Binding> translucentPullMlabBindings(OitInsertMode oitMode) {
        List<Binding> b = new ArrayList<>(List.of(
                new Binding(2, SSBO, MESH), new Binding(3, SSBO, MESH), new Binding(4, SSBO, MESH),
                new Binding(10, SAMPLER, FRAG),
                new Binding(16, UBO, MESH), new Binding(18, UBO, FRAG), new Binding(20, UBO, FRAG),
                new Binding(21, UBO, MESH)));
        VkOitPipelines.mlabBindings(b, oitMode);
        return b;
    }

    private static long compileProducerFrag(OitMode mode, boolean localRead, boolean linear) {
        Consumer<Compilation> extra = ShaderAssembly.NO_EXTRA;
        if (mode == OitMode.DEPTH_RANGE) {
            extra = extra.andThen(ctx -> ctx.define("_FLW_DEPTH_RANGE_LITE"));
        }
        if (localRead) {
            extra = extra.andThen(VkPrograms.LOCAL_READ);
        }
        String glsl = RenderPassShaders.assembleChunkOitFragment(mode, linear, extra,
                TerrainOverlayDraw.sources());
        String fsVk = VkShaderTransform.toVulkan(glsl, VkShaderTransform.Stage.FRAGMENT);
        return VkShaderCompiler.compileModule("translucent_oit_" + mode.name, fsVk, VkShaderCompiler.KIND_FRAGMENT);
    }

    private static void destroyModules(long... modules) {
        VkDevice device = VkContext.vkDevice();
        for (long module : modules) {
            if (module != 0L) {
                VK10.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    private static long compileModule(String path, int shadercKind) {
        return VkShaderCompiler.compileModule(path, assembleVk(path), shadercKind);
    }

    private static String assembleVk(String path) {
        return assembleVk(path, _ -> {
        });
    }

    private static String assembleVk(String path, Consumer<Compilation> extraDefines) {
        return ShaderAssembly.assemble(ctx -> {
            MeshShaderPrep.applyGlobalDefines(ctx);
            ctx.requireExtension("GL_EXT_buffer_reference_uvec2");
            if (path.startsWith("terrain/vk/translucent/") && !TerrainOverlayDraw.isEmpty()) {
                ctx.define("_FLW_TERRAIN_OVERLAY");
                ctx.define("_FLW_TERRAIN_RECEIVER_DATA");
            }
            extraDefines.accept(ctx);
        }, List.of(FlwPrograms.SOURCES.get(meshletId(path))));
    }

    private static Identifier meshletId(String path) {
        return Identifier.fromNamespaceAndPath("meshlet", path);
    }

    private static void warmOptional(ProgramAvailability.Feature feature, Runnable factory) {
        try {
            factory.run();
        } catch (ProgramAvailability.Failure failure) {
            throw failure;
        } catch (BackendUnavailableException failure) {
            throw new ProgramAvailability.Failure(feature, failure);
        }
    }

    private static long compileTranslucentPull(boolean depthRange, boolean receiverData) {
        var source = assembleVk("terrain/vk/translucent/pull.mesh", ctx -> {
            if (depthRange) ctx.define("_FLW_DEPTH_RANGE");
            if (receiverData) ctx.define("_FLW_TERRAIN_RECEIVER_DATA");
        });
        var name = "terrain/vk/translucent/pull" + (depthRange ? "_depth_range" : "")
                + (receiverData ? "_receivers" : "");
        return VkShaderCompiler.compileModule(name, source, Shaderc.shaderc_mesh_shader);
    }

    private static void clearCache(VkMeshPipeline[][] cache) {
        for (var byLinear : cache) Arrays.fill(byLinear, null);
    }

    private static List<Binding> translucentOverlayBindings(List<Binding> base, List<Identifier> sources) {
        if (sources.isEmpty()) return base;
        var result = new ArrayList<>(base);
        TerrainOverlayPipelines.vulkanLayouts(result, sources, MESH);
        return result;
    }

    public void warmUp() {
        VkPrograms programs = Objects.requireNonNull(VkPrograms.get());
        var terrain = BackendConfig.INSTANCE.terrainMode();
        if (terrain.ownsOpaque() && programs.usesGpuTerrain()) {
            drawPipeline(false, VK10.VK_FORMAT_R8G8B8A8_UNORM);
            drawPipeline(true, VK10.VK_FORMAT_R8G8B8A8_UNORM);
            emitPipeline();
        }
        if (!terrain.compositesTranslucent() || !programs.usesGpuTranslucentTerrain()) {
            return;
        }
        translucentEmitPipeline();
        translucentGatherPipeline();
        OitInsertMode insert = OitConfig.resolveInsertMode();
        if (insert != null) {
            warmOptional(ProgramAvailability.insert(insert), () -> translucentMlabPipeline(insert));
        } else {
            for (OitMode mode : OitMode.values()) {
                if (mode == OitMode.OFF) continue;
                translucentDrawPipeline(mode, false);
                if (programs.localRead()) {
                    warmOptional(ProgramAvailability.Feature.LOCAL_READ,
                            () -> translucentDrawPipeline(mode, true));
                }
            }
        }
    }

    public VkMeshPipeline drawPipeline(boolean cutout, int colorFormat) {
        var sources = TerrainOverlayDraw.sources();
        if (!sources.isEmpty()) {
            var linear = TerrainAtlasFilter.linear();
            if (activeOpaqueLinear != linear || activeOpaqueColorFormat != colorFormat
                    || !activeOpaqueSources.equals(sources)) {
                activeOpaqueSources = sources;
                activeOpaqueLinear = linear;
                activeOpaqueColorFormat = colorFormat;
                Arrays.fill(activeOpaqueDraw, null);
            }
            var index = cutout ? 1 : 0;
            if (activeOpaqueDraw[index] == null) {
                activeOpaqueDraw[index] = overlayPipelines.computeIfAbsent(
                        new OverlayKey(cutout, linear, colorFormat, sources), this::buildOverlay);
            }
            return activeOpaqueDraw[index];
        }
        int idx = cutout ? 1 : 0;
        int lin = TerrainAtlasFilter.linear() ? 1 : 0;
        if (drawPipelines[idx][lin] == null) {
            long task = 0;
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                task = compileModule("terrain/vk/task.task", Shaderc.shaderc_task_shader);
                mesh = compileModule("terrain/vk/mesh.mesh", Shaderc.shaderc_mesh_shader);
                frag = compileTerrainFrag(cutout, lin == 1);
                Blend opaque = new Blend(false, 0, 0, 0, 0, 0, 0, VkGraphicsPipeline.COLOR_WRITE_RGBA);
                layout = new VkDescriptorLayout(DRAW_BINDINGS, 0, 0);
                drawPipelines[idx][lin] = new VkMeshPipeline(layout, task, mesh, frag,
                        new int[]{colorFormat}, new Blend[]{opaque}, true, depthFormat);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(task, mesh, frag);
                throw t;
            }
        }
        return drawPipelines[idx][lin];
    }

    private VkMeshPipeline buildOverlay(OverlayKey key) {
        long task = 0, mesh = 0, frag = 0;
        VkDescriptorLayout layout = null;
        try {
            task = compileModule("terrain/vk/task.task", Shaderc.shaderc_task_shader);
            mesh = VkShaderCompiler.compileModule("terrain/vk/mesh_overlay",
                    assembleVk("terrain/vk/mesh.mesh", ctx -> ctx.define("_FLW_TERRAIN_OVERLAY")),
                    Shaderc.shaderc_mesh_shader);
            var source = TerrainPipelines.assembleOverlay(key.cutout, key.linear, key.sources, ctx -> {
                ctx.requireExtension("GL_EXT_mesh_shader");
                ctx.define("_FLW_MESH_PER_PRIMITIVE");
            });
            frag = VkShaderCompiler.compileModule("terrain/mesh_overlay",
                    VkShaderTransform.toVulkan(source, VkShaderTransform.Stage.FRAGMENT),
                    VkShaderCompiler.KIND_FRAGMENT);
            var bindings = new ArrayList<>(DRAW_BINDINGS);
            bindings.add(new Binding(TerrainOverlayPipelines.RECEIVER_BINDING, SSBO, MESH));
            TerrainOverlayPipelines.vulkanLayouts(bindings, key.sources, MESH);
            layout = new VkDescriptorLayout(bindings, 0, 0);
            var opaque = new Blend(false, 0, 0, 0, 0, 0, 0, VkGraphicsPipeline.COLOR_WRITE_RGBA);
            return new VkMeshPipeline(layout, task, mesh, frag, new int[]{key.colorFormat}, new Blend[]{opaque}, true,
                    depthFormat);
        } catch (Throwable failure) {
            if (layout != null) layout.delete();
            destroyModules(task, mesh, frag);
            throw failure;
        }
    }

    public VkComputePipeline emitPipeline() {
        if (emitPipeline == null) {
            long module = 0;
            VkDescriptorLayout layout = null;
            try {
                module = compileModule("terrain/vk/command_builder.comp", Shaderc.shaderc_compute_shader);
                layout = new VkDescriptorLayout(EMIT_BINDINGS, 0, 0);
                emitPipeline = new VkComputePipeline(layout, module);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(module);
                throw t;
            }
        }
        return emitPipeline;
    }

    public VkMeshPipeline translucentDrawPipeline(OitMode mode, boolean localRead) {
        return translucentDrawPipeline(mode, localRead, !TerrainOverlayDraw.isEmpty());
    }

    /**
     * The gathered payload keeps its shape through this frame even if its last overlay is removed.
     */
    public VkMeshPipeline translucentDrawPipeline(OitMode mode, boolean localRead, boolean receiverData) {
        var lin = TerrainAtlasFilter.linear() ? 1 : 0;
        var sources = TerrainOverlayDraw.sources();
        var variant = receiverData || !sources.isEmpty();
        var cache = localRead ? translucentDrawFolded[lin] : translucentDraw[lin];
        if (variant) {
            selectTranslucentSources(sources, receiverData);
            cache = localRead ? activeTranslucentDrawFolded[lin] : activeTranslucentDraw[lin];
        }
        var p = cache[mode.ordinal()];
        if (p != null) return p;
        var key = variant ? new TranslucentKey(lin == 1, mode, null, localRead, receiverData, sources) : null;
        if (variant) {
            p = translucentOverlays.get(key);
            if (p != null) {
                cache[mode.ordinal()] = p;
                return p;
            }
        }
        if (p == null) {
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                mesh = compileTranslucentPull(mode == OitMode.DEPTH_RANGE, receiverData);
                frag = compileProducerFrag(mode, localRead, lin == 1);
                if (localRead) {
                    layout = new VkDescriptorLayout(
                            translucentOverlayBindings(translucentPullDrawBindings(true), sources), 0, 0);
                    p = new VkMeshPipeline(layout, 0, mesh, frag,
                            VkOitPipelines.FOLDED_FORMATS, VkOitPipelines.foldedBlends(mode), false, depthFormat,
                            VkOitPipelines.foldedLocations(mode), VkOitPipelines.FOLDED_INPUT_INDICES);
                } else {
                    int[] colorFormats = switch (mode) {
                        case DEPTH_RANGE -> new int[]{VkOitPipelines.FMT_BOUNDS};
                        case GENERATE_COEFFICIENTS -> new int[]{FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F};
                        case EVALUATE -> new int[]{FMT_RGBA16F};
                        default -> throw new IllegalArgumentException("OitMode.OFF is not a producer mode");
                    };
                    Blend[] blends = new Blend[colorFormats.length];
                    Arrays.fill(blends,
                            mode == OitMode.DEPTH_RANGE ? VkGraphicsPipeline.max() : VkGraphicsPipeline.additive());
                    layout = new VkDescriptorLayout(
                            translucentOverlayBindings(translucentPullDrawBindings(false), sources), 0, 0);
                    p = new VkMeshPipeline(layout, 0, mesh, frag,
                            colorFormats, blends, false, depthFormat);
                }
                cache[mode.ordinal()] = p;
                if (variant) translucentOverlays.put(key, p);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(mesh, frag);
                throw t;
            }
        }
        return p;
    }

    public VkMeshPipeline translucentMlabPipeline(OitInsertMode oitMode) {
        return translucentMlabPipeline(oitMode, !TerrainOverlayDraw.isEmpty());
    }

    /**
     * Uses the current gather's receiver layout; overlay shading follows the surviving registrations.
     */
    public VkMeshPipeline translucentMlabPipeline(OitInsertMode oitMode, boolean receiverData) {
        var lin = TerrainAtlasFilter.linear() ? 1 : 0;
        var sources = TerrainOverlayDraw.sources();
        var variant = receiverData || !sources.isEmpty();
        var cache = translucentMlab[lin];
        if (variant) {
            selectTranslucentSources(sources, receiverData);
            cache = activeTranslucentMlab[lin];
        }
        var p = cache[oitMode.ordinal()];
        if (p != null) return p;
        var key = variant ? new TranslucentKey(lin == 1, OitMode.OFF, oitMode, false, receiverData, sources) : null;
        if (variant) {
            p = translucentOverlays.get(key);
            if (p != null) {
                cache[oitMode.ordinal()] = p;
                return p;
            }
        }
        if (p == null) {
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                mesh = compileTranslucentPull(false, receiverData);
                String glsl = RenderPassShaders.assembleChunkMlabFragment(oitMode, lin == 1, ShaderAssembly.NO_EXTRA,
                        sources);
                frag = VkShaderCompiler.compileModule("translucent_mlab_" + oitMode,
                        VkShaderTransform.toVulkan(glsl, VkShaderTransform.Stage.FRAGMENT),
                        VkShaderCompiler.KIND_FRAGMENT);
                layout = new VkDescriptorLayout(
                        translucentOverlayBindings(translucentPullMlabBindings(oitMode), sources), 0, 0);
                p = new VkMeshPipeline(layout, 0, mesh, frag,
                        new int[0], new VkGraphicsPipeline.Blend[0], false, depthFormat);
                cache[oitMode.ordinal()] = p;
                if (variant) translucentOverlays.put(key, p);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(mesh, frag);
                throw t;
            }
        }
        return p;
    }

    private void selectTranslucentSources(List<Identifier> sources, boolean receiverData) {
        if (activeTranslucentReceiverData == receiverData && activeTranslucentSources.equals(sources)) return;
        activeTranslucentSources = sources;
        activeTranslucentReceiverData = receiverData;
        clearCache(activeTranslucentDraw);
        clearCache(activeTranslucentDrawFolded);
        clearCache(activeTranslucentMlab);
    }

    public VkComputePipeline translucentEmitPipeline() {
        if (translucentEmit == null) {
            long module = 0;
            VkDescriptorLayout layout = null;
            try {
                module = compileModule("terrain/vk/translucent/command_builder.comp", Shaderc.shaderc_compute_shader);
                layout = new VkDescriptorLayout(TRANSLUCENT_EMIT_BINDINGS, 0, 0);
                translucentEmit = new VkComputePipeline(layout, module);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(module);
                throw t;
            }
        }
        return translucentEmit;
    }

    public VkComputePipeline translucentGatherPipeline() {
        boolean overlay = !TerrainOverlayDraw.isEmpty();
        var pipeline = overlay ? translucentGatherOverlay : translucentGather;
        if (pipeline == null) {
            long module = 0;
            VkDescriptorLayout layout = null;
            try {
                module = compileModule("terrain/vk/translucent/gather.comp", Shaderc.shaderc_compute_shader);
                var bindings = new ArrayList<>(TRANSLUCENT_GATHER_BINDINGS);
                if (overlay) bindings.add(new Binding(TerrainOverlayPipelines.RECEIVER_BINDING, SSBO, COMP));
                layout = new VkDescriptorLayout(bindings, 0, 0);
                pipeline = new VkComputePipeline(layout, module);
                if (overlay) translucentGatherOverlay = pipeline;
                else translucentGather = pipeline;
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(module);
                throw t;
            }
        }
        return pipeline;
    }

    public void destroy() {
        Arrays.fill(activeOpaqueDraw, null);
        activeOpaqueSources = List.of();
        activeTranslucentSources = List.of();
        activeTranslucentReceiverData = false;
        clearCache(activeTranslucentDraw);
        clearCache(activeTranslucentDrawFolded);
        clearCache(activeTranslucentMlab);
        for (var pipeline : translucentOverlays.values()) pipeline.delete();
        translucentOverlays.clear();
        if (translucentGatherOverlay != null) {
            translucentGatherOverlay.delete();
            translucentGatherOverlay = null;
        }
        overlayPipelines.values().forEach(VkMeshPipeline::delete);
        overlayPipelines.clear();
        for (VkMeshPipeline[] byLinear : drawPipelines) {
            for (int i = 0; i < byLinear.length; i++) {
                if (byLinear[i] != null) {
                    byLinear[i].delete();
                    byLinear[i] = null;
                }
            }
        }
        if (emitPipeline != null) {
            emitPipeline.delete();
            emitPipeline = null;
        }
        for (int lin = 0; lin < 2; lin++) {
            for (int i = 0; i < translucentDraw[lin].length; i++) {
                if (translucentDraw[lin][i] != null) {
                    translucentDraw[lin][i].delete();
                    translucentDraw[lin][i] = null;
                }
                if (translucentDrawFolded[lin][i] != null) {
                    translucentDrawFolded[lin][i].delete();
                    translucentDrawFolded[lin][i] = null;
                }
            }
        }
        for (int lin = 0; lin < 2; lin++) {
            for (int m = 0; m < translucentMlab[lin].length; m++) {
                if (translucentMlab[lin][m] != null) {
                    translucentMlab[lin][m].delete();
                    translucentMlab[lin][m] = null;
                }
            }
        }
        if (translucentEmit != null) {
            translucentEmit.delete();
            translucentEmit = null;
        }
        if (translucentGather != null) {
            translucentGather.delete();
            translucentGather = null;
        }
    }

    private record OverlayKey(boolean cutout, boolean linear, int colorFormat, List<Identifier> sources) {
    }

    private record TranslucentKey(boolean linear, OitMode mode, @Nullable OitInsertMode insert, boolean localRead,
                                  boolean receiverData, List<Identifier> sources) {
    }
}
