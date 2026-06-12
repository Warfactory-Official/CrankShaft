package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.MaterialShaders;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.shader.*;
import dev.engine_room.flywheel.lib.material.StandardMaterialShaders;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.EXTMeshShader;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkDevice;

import java.util.*;

/**
 * Pipelines for the {@code vk_mesh_shader} VISUAL tier: per-{@link InstanceType} EXT task+mesh+frag pipelines plus
 * the command-builder compute. The fragments are the SHARED meshvisual fragments run through {@link VkShaderTransform}.
 */
public final class VkMeshVisualPipelines {
    public static final int PUSH_BYTES = 48;
    public static final int COMMAND_STRIDE = 12;
    private static final int FMT_RGBA32F = VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
    private static final int FMT_RGBA16F = VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int TASK = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
    private static final int MESH = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
    private static final int FRAG = VK12.VK_SHADER_STAGE_FRAGMENT_BIT;
    private static final int COMP = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int SSBO = VkDescriptorLayout.TYPE_STORAGE_BUFFER;
    private static final int UBO = VkDescriptorLayout.TYPE_UNIFORM_BUFFER;
    private static final int SAMPLER = VkDescriptorLayout.TYPE_COMBINED_IMAGE_SAMPLER;
    private static final List<VkDescriptorLayout.Binding> BUILDER_BINDINGS = List.of(
            new VkDescriptorLayout.Binding(4, SSBO, COMP), new VkDescriptorLayout.Binding(15, SSBO, COMP));
    private final Map<SolidKey, VkMeshPipeline> solid = new HashMap<>();
    private final Map<CrumblingKey, VkMeshPipeline> crumbling = new HashMap<>();
    private final Map<OitKey, VkMeshPipeline[]> oit = new HashMap<>();
    private final Map<OitKey, VkMeshPipeline[]> oitFolded = new HashMap<>();
    private final Map<OitKey, VkMeshPipeline[]> mlab = new HashMap<>();
    private VkComputePipeline builder;

    private static List<VkDescriptorLayout.Binding> drawBindings(boolean oit, boolean localRead) {
        boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
        List<VkDescriptorLayout.Binding> b = new ArrayList<>(List.of(
                new VkDescriptorLayout.Binding(1, SSBO, TASK | MESH),
                new VkDescriptorLayout.Binding(2, SSBO, TASK | MESH),
                new VkDescriptorLayout.Binding(4, SSBO, TASK | MESH), new VkDescriptorLayout.Binding(5, SSBO, FRAG),
                new VkDescriptorLayout.Binding(6, SSBO, FRAG), new VkDescriptorLayout.Binding(7, SSBO, TASK | MESH),
                new VkDescriptorLayout.Binding(8, UBO, TASK), new VkDescriptorLayout.Binding(9, UBO, MESH | FRAG),
                new VkDescriptorLayout.Binding(16, UBO, MESH), new VkDescriptorLayout.Binding(17, UBO, FRAG),
                new VkDescriptorLayout.Binding(18, UBO, FRAG), new VkDescriptorLayout.Binding(19, UBO, FRAG),
                new VkDescriptorLayout.Binding(22, UBO, FRAG), new VkDescriptorLayout.Binding(23, SAMPLER, TASK)));
        if (!bindless) {
            b.add(new VkDescriptorLayout.Binding(10, SAMPLER, FRAG));
            b.add(new VkDescriptorLayout.Binding(11, SAMPLER, FRAG));
            b.add(new VkDescriptorLayout.Binding(12, SAMPLER, FRAG));
        }
        if (oit) {
            int oitRead = localRead ? VkDescriptorLayout.TYPE_INPUT_ATTACHMENT : SAMPLER;
            if (localRead || !bindless) {
                b.add(new VkDescriptorLayout.Binding(14, oitRead, FRAG));
                for (int i = 0; i < 4; i++) {
                    b.add(new VkDescriptorLayout.Binding(24 + i, oitRead, FRAG));
                }
            }
            if (!bindless) {
                b.add(new VkDescriptorLayout.Binding(15, SAMPLER, FRAG));
            }
        }
        return b;
    }

    // The mesh stage carries the same _FLW_DEBUG as its fragment (the MeshVertexOut block must match member-for-member).
    private static long compileMesh(MeshKey key, DebugMode debug) {
        String src = MeshVisualShaders.assembleVkMesh(key.type(), key.shaders()
                                                                     .vertexSource(),
                (VkCaps.BINDLESS_TEXTURES_NEGOTIATED ? VkPrograms.BINDLESS : ShaderAssembly.NO_EXTRA)
                        .andThen(MeshVisualShaders.clipExtra(key.type()))
                        .andThen(RenderPassShaders.debugExtra(debug)));
        return VkShaderCompiler.compileModule("meshvisual_vk_mesh" + (debug == DebugMode.OFF ? "" : "_debug"), src,
                Shaderc.shaderc_mesh_shader);
    }

    private static long compileTask(InstanceType<?> type) {
        return VkShaderCompiler.compileModule("meshvisual_vk_task", MeshVisualShaders.assembleVkTask(type),
                Shaderc.shaderc_task_shader);
    }

    private static long compileFragment(String glSource, String name) {
        String vk = VkShaderTransform.toVulkan(glSource, VkShaderTransform.Stage.FRAGMENT);
        return VkShaderCompiler.compileModule(name, vk, VkShaderCompiler.KIND_FRAGMENT);
    }

    private static void destroyModules(long... modules) {
        VkDevice device = VkContext.vkDevice();
        for (long module : modules) {
            if (module != 0L) {
                VK12.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    public static long deviceAddress(long vkBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(vkBuffer);
            return VK12.vkGetBufferDeviceAddress(VkContext.vkDevice(), info);
        }
    }

    public VkMeshPipeline crumblingPipeline(InstanceType<?> type, int colorFormat, int depthFormat) {
        return crumbling.computeIfAbsent(new CrumblingKey(type, FrameUniforms.debugMode()), k -> {
            InstanceType<?> t = k.type();
            var debug = RenderPassShaders.debugExtra(k.debug());
            String debugName = k.debug() == DebugMode.OFF ? "" : "_debug";
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                mesh = VkShaderCompiler.compileModule("meshvisual_vk_crumbling_mesh" + debugName,
                        MeshVisualShaders.assembleVkCrumblingMesh(t, debug), Shaderc.shaderc_mesh_shader);
                frag = compileFragment(
                        MeshVisualShaders.assembleFragment(true, StandardMaterialShaders.DEFAULT.fragmentSource(),
                                debug), "meshvisual_vk_crumbling_frag" + debugName);
                List<VkDescriptorLayout.Binding> b = List.of(
                        new VkDescriptorLayout.Binding(1, SSBO, MESH), new VkDescriptorLayout.Binding(5, SSBO, FRAG),
                        new VkDescriptorLayout.Binding(6, SSBO, FRAG),
                        new VkDescriptorLayout.Binding(9, UBO, MESH | FRAG),
                        new VkDescriptorLayout.Binding(10, SAMPLER, FRAG),
                        new VkDescriptorLayout.Binding(11, SAMPLER, FRAG),
                        new VkDescriptorLayout.Binding(12, SAMPLER, FRAG),
                        new VkDescriptorLayout.Binding(13, SAMPLER, FRAG),
                        new VkDescriptorLayout.Binding(16, UBO, MESH), new VkDescriptorLayout.Binding(17, UBO, FRAG),
                        new VkDescriptorLayout.Binding(18, UBO, FRAG), new VkDescriptorLayout.Binding(19, UBO, FRAG),
                        new VkDescriptorLayout.Binding(22, UBO, FRAG));
                layout = new VkDescriptorLayout(b, PUSH_BYTES, MESH);
                return new VkMeshPipeline(layout, 0L, mesh, frag,
                        new int[]{colorFormat}, new VkGraphicsPipeline.Blend[]{VkGraphicsPipeline.crumbling()},
                        false, depthFormat, null, null, 10.0F, 1.0F);
            } catch (Throwable ex) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(mesh, frag);
                throw ex;
            }
        });
    }

    public VkMeshPipeline solidPipeline(InstanceType<?> type, Material material, int colorFormat, int depthFormat) {
        return solid.computeIfAbsent(SolidKey.of(type, material, colorFormat, depthFormat), key -> {
            boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
            long task = 0;
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                task = compileTask(key.mesh()
                                      .type());
                mesh = compileMesh(key.mesh(), key.debug());
                String fsGl = MeshVisualShaders.assembleFragment(false, key.mesh()
                                                                           .shaders()
                                                                           .fragmentSource(),
                        (bindless ? VkPrograms.BINDLESS : ShaderAssembly.NO_EXTRA).andThen(
                                                                                          MeshVisualShaders.VK_MESH_F16)
                                                                                  .andThen(MeshVisualShaders.clipExtra(
                                                                                          type))
                                                                                  .andThen(RenderPassShaders.debugExtra(
                                                                                          key.debug())));
                frag = compileFragment(fsGl, "meshvisual_vk_frag"
                        + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug().getSerializedName()));
                layout = new VkDescriptorLayout(drawBindings(false, false), PUSH_BYTES, TASK | MESH, bindless);
                VkGraphicsPipeline.Config config = VkGraphicsPipeline.material(key.colorFormat(), key.depthFormat(),
                        key.transparency(), key.depthTest(), key.depthWrite(), key.colorWrite(), key.cull(),
                        key.polygonOffset());
                return new VkMeshPipeline(layout, task, mesh, frag, config);
            } catch (Throwable ex) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(task, mesh, frag);
                throw ex;
            }
        });
    }

    public VkMeshPipeline oitPipeline(InstanceType<?> type, Material material, OitMode mode, int depthFormat,
                                      boolean folded) {
        Map<OitKey, VkMeshPipeline[]> cache = folded ? oitFolded : oit;
        OitKey key = OitKey.of(type, material);
        VkMeshPipeline[] arr = cache.computeIfAbsent(key, k -> new VkMeshPipeline[OitMode.values().length]);
        int idx = mode.ordinal();
        if (arr[idx] == null) {
            boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
            long task = 0;
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                task = compileTask(type);
                mesh = compileMesh(key.mesh(), key.debug());
                String fsGl = MeshVisualShaders.assembleOitFragment(mode, key.mesh()
                                                                             .shaders()
                                                                             .fragmentSource(), folded,
                        (bindless ? VkPrograms.BINDLESS : ShaderAssembly.NO_EXTRA).andThen(
                                                                                          MeshVisualShaders.VK_MESH_F16)
                                                                                  .andThen(MeshVisualShaders.clipExtra(
                                                                                          type))
                                                                                  .andThen(RenderPassShaders.debugExtra(
                                                                                          key.debug())));
                frag = compileFragment(fsGl, "meshvisual_vk_oit_" + mode.name + (folded ? "_folded" : "")
                        + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug().getSerializedName()));
                if (folded) {
                    layout = new VkDescriptorLayout(drawBindings(true, true), PUSH_BYTES, TASK | MESH, bindless);
                    arr[idx] = new VkMeshPipeline(layout, task, mesh, frag,
                            VkOitPipelines.FOLDED_FORMATS, VkOitPipelines.foldedBlends(mode), false, depthFormat,
                            VkOitPipelines.foldedLocations(mode), VkOitPipelines.FOLDED_INPUT_INDICES);
                } else {
                    int[] colorFormats = switch (mode) {
                        case DEPTH_RANGE -> new int[]{FMT_RGBA32F};
                        case GENERATE_COEFFICIENTS -> new int[]{FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F};
                        case EVALUATE -> new int[]{FMT_RGBA16F};
                        default -> throw new IllegalArgumentException("OitMode.OFF is not a producer mode");
                    };
                    VkGraphicsPipeline.Blend[] blends = new VkGraphicsPipeline.Blend[colorFormats.length];
                    Arrays.fill(blends,
                            mode == OitMode.DEPTH_RANGE ? VkGraphicsPipeline.max() : VkGraphicsPipeline.additive());
                    layout = new VkDescriptorLayout(drawBindings(true, false), PUSH_BYTES, TASK | MESH, bindless);
                    arr[idx] = new VkMeshPipeline(layout, task, mesh, frag, colorFormats, blends, false, depthFormat);
                }
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(task, mesh, frag);
                throw t;
            }
        }
        return arr[idx];
    }

    public VkMeshPipeline mlabPipeline(InstanceType<?> type, Material material, OitInsertMode oitMode,
                                       int depthFormat) {
        OitKey key = OitKey.of(type, material);
        VkMeshPipeline[] arr = mlab.computeIfAbsent(key, k -> new VkMeshPipeline[OitInsertMode.values().length]);
        int idx = oitMode.ordinal();
        if (arr[idx] == null) {
            boolean bindless = VkCaps.BINDLESS_TEXTURES_NEGOTIATED;
            long task = 0;
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                task = compileTask(type);
                mesh = compileMesh(key.mesh(), key.debug());
                String fsGl = MeshVisualShaders.assembleMlabOitFragment(oitMode, key.mesh()
                                                                                    .shaders()
                                                                                    .fragmentSource(),
                        (bindless ? VkPrograms.BINDLESS : ShaderAssembly.NO_EXTRA).andThen(
                                                                                          MeshVisualShaders.VK_MESH_F16)
                                                                                  .andThen(MeshVisualShaders.clipExtra(
                                                                                          type))
                                                                                  .andThen(RenderPassShaders.debugExtra(
                                                                                          key.debug())));
                frag = compileFragment(fsGl, "meshvisual_vk_mlab_" + oitMode
                        + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug().getSerializedName()));
                List<VkDescriptorLayout.Binding> b = new ArrayList<>(drawBindings(false, false));
                VkOitPipelines.mlabBindings(b, oitMode);
                layout = new VkDescriptorLayout(b, PUSH_BYTES, TASK | MESH, bindless);
                arr[idx] = new VkMeshPipeline(layout, task, mesh, frag, new int[0], new VkGraphicsPipeline.Blend[0],
                        false, depthFormat);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(task, mesh, frag);
                throw t;
            }
        }
        return arr[idx];
    }

    public VkComputePipeline builderPipeline() {
        if (builder == null) {
            long module = 0;
            VkDescriptorLayout layout = null;
            try {
                module = VkShaderCompiler.compileModule("meshvisual_vk_command_builder",
                        MeshVisualShaders.assembleVkCommandBuilder(), VkShaderCompiler.KIND_COMPUTE);
                layout = new VkDescriptorLayout(BUILDER_BINDINGS, Integer.BYTES, COMP);
                builder = new VkComputePipeline(layout, module);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(module);
                throw t;
            }
        }
        return builder;
    }

    public void delete() {
        for (VkMeshPipeline p : solid.values()) {
            p.delete();
        }
        solid.clear();
        for (VkMeshPipeline p : crumbling.values()) {
            p.delete();
        }
        crumbling.clear();
        for (VkMeshPipeline[] arr : oit.values()) {
            for (VkMeshPipeline p : arr) {
                if (p != null) {
                    p.delete();
                }
            }
        }
        oit.clear();
        for (VkMeshPipeline[] arr : oitFolded.values()) {
            for (VkMeshPipeline p : arr) {
                if (p != null) {
                    p.delete();
                }
            }
        }
        oitFolded.clear();
        for (VkMeshPipeline[] arr : mlab.values()) {
            for (VkMeshPipeline p : arr) {
                if (p != null) {
                    p.delete();
                }
            }
        }
        mlab.clear();
        if (builder != null) {
            builder.delete();
            builder = null;
        }
    }

    private record MeshKey(InstanceType<?> type, MaterialShaders shaders) {
        static MeshKey of(InstanceType<?> type, Material material) {
            return new MeshKey(type, material.shaders());
        }
    }

    private record OitKey(MeshKey mesh, int cutoutGen, int fogGen, DebugMode debug) {
        static OitKey of(InstanceType<?> type, Material material) {
            return new OitKey(MeshKey.of(type, material),
                    MaterialShaderIndices.cutoutSources()
                                         .all()
                                         .size(),
                    MaterialShaderIndices.fogSources()
                                         .all()
                                         .size(),
                    FrameUniforms.debugMode());
        }
    }

    /**
     * Solid pipeline key: the mesh key + the material's fixed-function state (mirrors VkUberPipelines' draw key);
     * registry generations key fresh compiles covering later-registered sources.
     */
    private record SolidKey(MeshKey mesh, int colorFormat, int depthFormat, Transparency transparency,
                            DepthTest depthTest, boolean depthWrite, boolean colorWrite, boolean cull,
                            boolean polygonOffset, int cutoutGen, int fogGen, DebugMode debug) {
        static SolidKey of(InstanceType<?> type, Material material, int colorFormat, int depthFormat) {
            return new SolidKey(MeshKey.of(type, material), colorFormat, depthFormat, material.transparency(),
                    material.depthTest(), material.writeMask()
                                                  .depth(), material.writeMask()
                                                                    .color(), material.backfaceCulling(),
                    material.polygonOffset(),
                    MaterialShaderIndices.cutoutSources()
                                         .all()
                                         .size(),
                    MaterialShaderIndices.fogSources()
                                         .all()
                                         .size(),
                    // Read per request like the registry generations: a /flywheel debug shader toggle keys a
                    // fresh pipeline next frame (solidPipeline resolves per multiDraw per frame). OIT/crumbling
                    // stay debug-free, matching the vertex paths.
                    FrameUniforms.debugMode());
        }
    }

    private record CrumblingKey(InstanceType<?> type, DebugMode debug) {
    }
}
