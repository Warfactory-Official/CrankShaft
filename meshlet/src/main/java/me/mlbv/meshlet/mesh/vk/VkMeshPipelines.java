// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock

package me.mlbv.meshlet.mesh.vk;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainPipelines;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.Binding;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkGraphicsPipeline.Blend;
import dev.engine_room.flywheel.backend.vk.shader.VkMeshPipeline;
import dev.engine_room.flywheel.backend.compile.VkOitPipelines;
import dev.engine_room.flywheel.backend.compile.VkPrograms;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderCompiler;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderTransform;

import me.mlbv.meshlet.mesh.shared.MeshShaderPrep;
import net.minecraft.resources.Identifier;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;

/**
 * Compiles + owns the pipelines of the vk_mesh_shader terrain tier (task+mesh+frag DRAW + EMIT compute),
 * lazy-compiled at first draw. The VK twin of {@code GlMeshPipelines}; layout/pipeline infrastructure is
 * CrankShaft's -- only the binding TABLES (the tier's shader ABI) live here.
 */
public final class VkMeshPipelines {
    private static final int FMT_RGBA32F = VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
    private static final int FMT_RGBA16F = VK12.VK_FORMAT_R16G16B16A16_SFLOAT;

    private static final int TASK = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
    private static final int MESH = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
    private static final int FRAG = VK12.VK_SHADER_STAGE_FRAGMENT_BIT;
    private static final int COMP = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int SSBO = VkDescriptorLayout.TYPE_STORAGE_BUFFER;
    private static final int UBO = VkDescriptorLayout.TYPE_UNIFORM_BUFFER;
    private static final int SAMPLER = VkDescriptorLayout.TYPE_COMBINED_IMAGE_SAMPLER;

    // DRAW (task+mesh+frag): binding numbers mirror MDI's VkShaderTransform ABI exactly -- Sampler0 atlas @10 (frag),
    // Sampler2 lightmap @12 (mesh bakes vertexColor), Projection @16 + Globals @20 + ChunkSection(ModelViewMat) @21
    // (the SAME vanilla buffers terrain_solid.vsh binds, for the bit-exact Pass-A-matching transform), Fog @18 (frag).
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

    private final VkMeshPipeline[][] drawPipelines = new VkMeshPipeline[2][2];
    private VkComputePipeline emitPipeline;
    private VkGatherPipeline gatherPipeline;
    private final VkMeshPipeline[][] translucentDraw = new VkMeshPipeline[2][OitMode.values().length];
    private final VkMeshPipeline[][] translucentDrawFolded = new VkMeshPipeline[2][OitMode.values().length];
    private final VkMeshPipeline[][] translucentMlab = new VkMeshPipeline[2][OitInsertMode.values().length];
    private VkComputePipeline translucentEmit;
    private VkComputePipeline translucentGather;

    public void warmUp() {
        drawPipeline(false, VK12.VK_FORMAT_R8G8B8A8_UNORM, VK12.VK_FORMAT_D32_SFLOAT);
        drawPipeline(true, VK12.VK_FORMAT_R8G8B8A8_UNORM, VK12.VK_FORMAT_D32_SFLOAT);
        emitPipeline();
        gatherPipeline();
        translucentEmitPipeline();
        translucentGatherPipeline();
        boolean localRead = VkCaps.DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED;
        for (OitMode mode : OitMode.values()) {
            if (mode == OitMode.OFF) {
                continue;
            }
            translucentDrawPipeline(mode, VK12.VK_FORMAT_D32_SFLOAT, false);
            if (localRead) {
                translucentDrawPipeline(mode, VK12.VK_FORMAT_D32_SFLOAT, true);
            }
        }
        for (OitInsertMode mode : OitInsertMode.values()) {
            if (mode == OitInsertMode.MLAB && !VkCaps.FRAGMENT_SHADER_INTERLOCK_NEGOTIATED) {
                continue;
            }
            translucentMlabPipeline(mode, VK12.VK_FORMAT_D32_SFLOAT);
        }
    }

    public VkMeshPipeline drawPipeline(boolean cutout, int colorFormat, int depthFormat) {
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
                        new int[] {colorFormat}, new Blend[] {opaque}, true, depthFormat);
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

    private static long compileTerrainFrag(boolean cutout, boolean linear) {
        String fsVk = VkShaderTransform.toVulkan(TerrainPipelines.assembleFragment(cutout, linear, ctx -> {
            ctx.requireExtension("GL_EXT_mesh_shader");
            ctx.define("_FLW_MESH_PER_PRIMITIVE");
        }), VkShaderTransform.Stage.FRAGMENT);
        return VkShaderCompiler.compileModule(cutout ? "terrain_cutout" : "terrain_solid", fsVk, VkShaderCompiler.KIND_FRAGMENT);
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

    public VkMeshPipeline translucentDrawPipeline(OitMode mode, int depthFormat, boolean localRead) {
        int lin = TerrainAtlasFilter.linear() ? 1 : 0;
        VkMeshPipeline[] cache = localRead ? translucentDrawFolded[lin] : translucentDraw[lin];
        VkMeshPipeline p = cache[mode.ordinal()];
        if (p == null) {
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                mesh = mode == OitMode.DEPTH_RANGE
                        ? VkShaderCompiler.compileModule("terrain/vk/translucent/pull_depth_range",
                                assembleVk("terrain/vk/translucent/pull.mesh", ctx -> ctx.define("_FLW_DEPTH_RANGE")),
                                Shaderc.shaderc_mesh_shader)
                        : compileModule("terrain/vk/translucent/pull.mesh", Shaderc.shaderc_mesh_shader);
                frag = compileProducerFrag(mode, localRead, lin == 1);
                if (localRead) {
                    layout = new VkDescriptorLayout(translucentPullDrawBindings(true), 0, 0);
                    p = new VkMeshPipeline(layout, 0, mesh, frag,
                            VkOitPipelines.FOLDED_FORMATS, VkOitPipelines.foldedBlends(mode), false, depthFormat,
                            VkOitPipelines.foldedLocations(mode), VkOitPipelines.FOLDED_INPUT_INDICES);
                } else {
                    int[] colorFormats = switch (mode) {
                        case DEPTH_RANGE -> new int[] {FMT_RGBA32F};
                        case GENERATE_COEFFICIENTS -> new int[] {FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F};
                        case EVALUATE -> new int[] {FMT_RGBA16F};
                        default -> throw new IllegalArgumentException("OitMode.OFF is not a producer mode");
                    };
                    Blend[] blends = new Blend[colorFormats.length];
                    Arrays.fill(blends, mode == OitMode.DEPTH_RANGE ? VkGraphicsPipeline.max() : VkGraphicsPipeline.additive());
                    layout = new VkDescriptorLayout(translucentPullDrawBindings(false), 0, 0);
                    p = new VkMeshPipeline(layout, 0, mesh, frag,
                            colorFormats, blends, false, depthFormat);
                }
                cache[mode.ordinal()] = p;
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

    public VkMeshPipeline translucentMlabPipeline(OitInsertMode oitMode, int depthFormat) {
        int lin = TerrainAtlasFilter.linear() ? 1 : 0;
        VkMeshPipeline p = translucentMlab[lin][oitMode.ordinal()];
        if (p == null) {
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                mesh = compileModule("terrain/vk/translucent/pull.mesh", Shaderc.shaderc_mesh_shader);
                String glsl = RenderPassShaders.assembleChunkMlabFragment(oitMode, lin == 1);
                frag = VkShaderCompiler.compileModule("translucent_mlab_" + oitMode,
                        VkShaderTransform.toVulkan(glsl, VkShaderTransform.Stage.FRAGMENT), VkShaderCompiler.KIND_FRAGMENT);
                layout = new VkDescriptorLayout(translucentPullMlabBindings(oitMode), 0, 0);
                p = new VkMeshPipeline(layout, 0, mesh, frag,
                        new int[0], new VkGraphicsPipeline.Blend[0], false, depthFormat);
                translucentMlab[lin][oitMode.ordinal()] = p;
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

    private static List<Binding> translucentPullMlabBindings(OitInsertMode oitMode) {
        List<Binding> b = new ArrayList<>(List.of(
                new Binding(2, SSBO, MESH), new Binding(3, SSBO, MESH), new Binding(4, SSBO, MESH),
                new Binding(10, SAMPLER, FRAG),
                new Binding(16, UBO, MESH), new Binding(18, UBO, FRAG), new Binding(20, UBO, FRAG),
                new Binding(21, UBO, MESH)));
        VkOitPipelines.mlabBindings(b, oitMode);
        return b;
    }

    public VkGatherPipeline gatherPipeline() {
        if (gatherPipeline == null) {
            ByteBuffer comp = VkShaderCompiler.compileToSpirv("terrain/vk/gather.comp",
                    assembleVk("terrain/vk/gather.comp"), Shaderc.shaderc_compute_shader);
            try {
                gatherPipeline = new VkGatherPipeline(comp);
            } finally {
                MemoryUtil.memFree(comp);
            }
        }
        return gatherPipeline;
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
        if (translucentGather == null) {
            long module = 0;
            VkDescriptorLayout layout = null;
            try {
                module = compileModule("terrain/vk/translucent/gather.comp", Shaderc.shaderc_compute_shader);
                layout = new VkDescriptorLayout(TRANSLUCENT_GATHER_BINDINGS, 0, 0);
                translucentGather = new VkComputePipeline(layout, module);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(module);
                throw t;
            }
        }
        return translucentGather;
    }

    private static long compileProducerFrag(OitMode mode, boolean localRead, boolean linear) {
        Consumer<Compilation> extra = ShaderAssembly.NO_EXTRA;
        if (mode == OitMode.DEPTH_RANGE) {
            extra = extra.andThen(ctx -> ctx.define("_FLW_DEPTH_RANGE_LITE"));
        }
        if (localRead) {
            extra = extra.andThen(VkPrograms.LOCAL_READ);
        }
        String glsl = RenderPassShaders.assembleChunkOitFragment(mode, linear, extra);
        String fsVk = VkShaderTransform.toVulkan(glsl, VkShaderTransform.Stage.FRAGMENT);
        return VkShaderCompiler.compileModule("translucent_oit_" + mode.name, fsVk, VkShaderCompiler.KIND_FRAGMENT);
    }

    private static void destroyModules(long... modules) {
        VkDevice device = VkContext.vkDevice();
        for (long module : modules) {
            if (module != 0L) {
                VK12.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    private static long compileModule(String path, int shadercKind) {
        return VkShaderCompiler.compileModule(path, assembleVk(path), shadercKind);
    }

    private static String assembleVk(String path) {
        return assembleVk(path, ctx -> {
        });
    }

    private static String assembleVk(String path, Consumer<Compilation> extraDefines) {
        return ShaderAssembly.assemble(ctx -> {
            MeshShaderPrep.applyGlobalDefines(ctx);
            extraDefines.accept(ctx);
        }, List.of(FlwPrograms.SOURCES.get(meshletId(path))));
    }

    private static Identifier meshletId(String path) {
        return Identifier.fromNamespaceAndPath("meshlet", path);
    }

    public void destroy() {
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
        if (gatherPipeline != null) {
            gatherPipeline.destroy();
            gatherPipeline = null;
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
}
