package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.backend.BackendConfig;
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
import dev.engine_room.flywheel.backend.vk.shader.*;
import dev.engine_room.flywheel.lib.material.StandardMaterialShaders;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Pipelines for the {@code vk_mesh_shader} VISUAL tier: per-{@link InstanceType} EXT task+mesh+frag pipelines plus
 * the command-builder compute. The fragments are the SHARED meshvisual fragments run through {@link VkShaderTransform}.
 */
public final class VkMeshVisualPipelines {
    public static final int PUSH_BYTES = 48;
    public static final int COMMAND_STRIDE = 12;
    private static final int FMT_RGBA16F = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int TASK = EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
    private static final int MESH = EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
    private static final int FRAG = VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
    private static final int COMP = VK10.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int SSBO = VkDescriptorLayout.TYPE_STORAGE_BUFFER;
    private static final int UBO = VkDescriptorLayout.TYPE_UNIFORM_BUFFER;
    private static final int SAMPLER = VkDescriptorLayout.TYPE_COMBINED_IMAGE_SAMPLER;
    private static final List<VkDescriptorLayout.Binding> BUILDER_BINDINGS = List.of(
            new VkDescriptorLayout.Binding(4, SSBO, COMP), new VkDescriptorLayout.Binding(15, SSBO, COMP));
    private final Map<SolidKey, ProgramVersions<VkAsyncBuilds.Build<?>>> solid = new HashMap<>();
    private final Map<CrumblingKey, ProgramVersions<VkAsyncBuilds.Build<?>>> crumbling = new HashMap<>();
    private final Map<OitGroupKey, ProgramVersions<VkAsyncBuilds.Build<?>[]>> oit = new HashMap<>();
    private final Map<MlabKey, ProgramVersions<VkAsyncBuilds.Build<?>>> mlab = new HashMap<>();
    private VkComputePipeline builder;

    private static List<VkDescriptorLayout.Binding> drawBindings(boolean oit, boolean localRead, LightShader _,
                                                                 MaterialShaders shaders, boolean bindless) {
        int frameStages = shaders.vertexSource().equals(StandardMaterialShaders.LINE.vertexSource())
                ? TASK | MESH : TASK;
        List<VkDescriptorLayout.Binding> b = new ArrayList<>(List.of(
                new VkDescriptorLayout.Binding(1, SSBO, TASK | MESH),
                new VkDescriptorLayout.Binding(2, SSBO, TASK | MESH),
                new VkDescriptorLayout.Binding(4, SSBO, TASK | MESH), new VkDescriptorLayout.Binding(5, SSBO, FRAG),
                new VkDescriptorLayout.Binding(6, SSBO, FRAG), new VkDescriptorLayout.Binding(7, SSBO, TASK | MESH),
                new VkDescriptorLayout.Binding(8, UBO, frameStages),
                new VkDescriptorLayout.Binding(9, UBO, MESH | FRAG),
                new VkDescriptorLayout.Binding(16, UBO, MESH), new VkDescriptorLayout.Binding(17, UBO, FRAG),
                new VkDescriptorLayout.Binding(18, UBO, FRAG), new VkDescriptorLayout.Binding(19, UBO, FRAG),
                new VkDescriptorLayout.Binding(22, UBO, MESH | FRAG),
                new VkDescriptorLayout.Binding(23, SAMPLER, TASK)));
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

    private static String meshSource(MeshKey key, DebugMode debug, boolean bindless) {
        return MeshVisualShaders.assembleVkMesh(key.type(), key.shaders()
                                                               .vertexSource(),
                (bindless ? VkPrograms.BINDLESS : ShaderAssembly.NO_EXTRA)
                        .andThen(MeshVisualShaders.clipExtra(key.type()))
                        .andThen(RenderPassShaders.debugExtra(debug))
                        .andThen(embeddedExtra(key.embedded()))
                        .andThen(key.shaders().vertexSource().equals(StandardMaterialShaders.LINE.vertexSource())
                                ? ctx -> ctx.define("FLW_LINE_VK_MESH") : ShaderAssembly.NO_EXTRA));
    }

    private static long compileMesh(String source, DebugMode debug) {
        return VkShaderCompiler.compileModule("meshvisual_vk_mesh" + (debug == DebugMode.OFF ? "" : "_debug"), source,
                Shaderc.shaderc_mesh_shader);
    }

    private static Consumer<Compilation> embeddedExtra(boolean embedded) {
        return embedded ? RenderPassShaders.EMBEDDED : ShaderAssembly.NO_EXTRA;
    }

    private static Consumer<Compilation> fragmentExtra(MeshKey mesh, DebugMode debug, boolean emission,
                                                       boolean bindless) {
        return (bindless ? VkPrograms.BINDLESS : ShaderAssembly.NO_EXTRA)
                .andThen(MeshVisualShaders.VK_MESH_F16)
                .andThen(MeshVisualShaders.clipExtra(mesh.type()))
                .andThen(RenderPassShaders.debugExtra(debug))
                .andThen(emission ? RenderPassShaders.EMISSION_PRODUCER : ShaderAssembly.NO_EXTRA)
                .andThen(embeddedExtra(mesh.embedded()));
    }

    private static long compileTask(String source) {
        return VkPrograms.optional(ProgramAvailability.Feature.MESH,
                () -> VkShaderCompiler.compileModule("meshvisual_vk_task", source, Shaderc.shaderc_task_shader));
    }

    private static boolean bindless() {
        boolean bindless = VkPrograms.bindlessTexturesEnabled();
        if (bindless) VkBindlessTable.setLayoutHandle();
        return bindless;
    }

    private static VkAsyncBuilds.Build<?> build(Supplier<VkMeshPipeline> create) {
        return VkAsyncBuilds.request(create, VkMeshPipeline::delete);
    }

    private static <K> @Nullable Selection select(Map<K, ProgramVersions<VkAsyncBuilds.Build<?>>> versions, K key,
                                                  ProgramGeneration current,
                                                  Function<? super K, VkAsyncBuilds.Build<?>> request) {
        var entry = versions.computeIfAbsent(key, _ -> new ProgramVersions<>(VkAsyncBuilds.TRACKER));
        var build = entry.select(current, key, request);
        return build == null ? null : new Selection((VkMeshPipeline) build.result(), entry.generation());
    }

    /**
     * A built pipeline and the generation it covers; per instance type, so only its material registries bound it.
     */
    public record Selection(VkMeshPipeline pipeline, ProgramGeneration generation) {
    }

    private static long compileFragment(String glSource, String name) {
        String vk = VkShaderTransform.toVulkan(glSource, VkShaderTransform.Stage.FRAGMENT);
        return VkShaderCompiler.compileModule(name, vk, VkShaderCompiler.KIND_FRAGMENT);
    }

    private static void destroyModules(long... modules) {
        VkDevice device = VkContext.vkDevice();
        for (long module : modules) {
            if (module != 0L) {
                VK10.vkDestroyShaderModule(device, module, null);
            }
        }
    }

    public static long deviceAddress(long vkBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(vkBuffer);
            return VK12.vkGetBufferDeviceAddress(VkContext.vkDevice(), info);
        }
    }

    /**
     * Null until built; builds it.
     */
    public @Nullable VkMeshPipeline crumblingPipeline(Material crumblingMaterial, InstanceType<?> type,
                                                      int colorFormat, int depthFormat) {
        var key = new CrumblingKey(type, FrameUniforms.debugMode(), crumblingMaterial.depthTest(),
                crumblingMaterial.backfaceCulling(), BackendConfig.INSTANCE.lightSmoothness(), colorFormat,
                depthFormat);
        var selection = select(crumbling, key, ProgramGeneration.NONE, VkMeshVisualPipelines::requestCrumbling);
        return selection == null ? null : selection.pipeline();
    }

    private static VkAsyncBuilds.Build<?> requestCrumbling(CrumblingKey k) {
        var debug = RenderPassShaders.debugExtra(k.debug());
        String meshSource = MeshVisualShaders.assembleVkCrumblingMesh(k.type(), debug);
        String fsGl = MeshVisualShaders.assembleCrumblingFragment(k.smoothness(), debug);
        String debugName = k.debug() == DebugMode.OFF ? "" : "_debug";
        return build(() -> {
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                mesh = VkShaderCompiler.compileModule("meshvisual_vk_crumbling_mesh" + debugName, meshSource,
                        Shaderc.shaderc_mesh_shader);
                frag = compileFragment(fsGl, "meshvisual_vk_crumbling_frag" + debugName);
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
                        new int[]{k.colorFormat()}, new VkGraphicsPipeline.Blend[]{VkGraphicsPipeline.crumbling()},
                        VkGraphicsPipeline.compareOp(k.depthTest()),
                        k.cull() ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE, k.depthFormat(), null, null,
                        10.0F, 1.0F);
            } catch (Throwable ex) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(mesh, frag);
                if (ex instanceof BackendUnavailableException failure) {
                    throw VkPrograms.tagged(ProgramAvailability.Feature.MESH, failure);
                }
                throw ex;
            }
        });
    }

    /**
     * The newest built solid pipeline, null before the first; builds the current generation's. {@code embedded}: the
     * {@link RenderPassShaders#readsEmbedded} variant of an embedded run.
     */
    public @Nullable Selection solidPipeline(InstanceType<?> type, Material material, boolean embedded,
                                             int colorFormat, int depthFormat) {
        return select(solid, SolidKey.of(type, material, embedded, colorFormat, depthFormat),
                ProgramGeneration.current().materials(), VkMeshVisualPipelines::requestSolid);
    }

    private static VkAsyncBuilds.Build<?> requestSolid(SolidKey key) {
        boolean bindless = bindless();
        String taskSource = MeshVisualShaders.assembleVkTask(key.mesh().type());
        String meshSource = meshSource(key.mesh(), key.debug(), bindless);
        String fsGl = MeshVisualShaders.assembleFragment(key.light(), key.mesh().shaders().fragmentSource(),
                key.smoothness(), fragmentExtra(key.mesh(), key.debug(), false, bindless));
        List<VkDescriptorLayout.Binding> bindings = drawBindings(false, false, key.light(), key.mesh().shaders(),
                bindless);
        return build(() -> {
            long task = 0;
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                task = compileTask(taskSource);
                mesh = compileMesh(meshSource, key.debug());
                frag = compileFragment(fsGl, "meshvisual_vk_frag"
                        + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug().getSerializedName()));
                layout = new VkDescriptorLayout(bindings, PUSH_BYTES, TASK | MESH, bindless);
                VkGraphicsPipeline.Config config = VkGraphicsPipeline.material(key.colorFormat(), key.depthFormat(),
                        key.transparency(), key.depthTest(), key.depthWrite(), key.colorWrite(), key.cull(),
                        key.polygonOffset());
                return new VkMeshPipeline(layout, task, mesh, frag, config);
            } catch (Throwable ex) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(task, mesh, frag);
                if (ex instanceof BackendUnavailableException failure) {
                    throw VkPrograms.tagged(bindless ? ProgramAvailability.Feature.BINDLESS
                            : ProgramAvailability.Feature.MESH, failure);
                }
                throw ex;
            }
        });
    }

    /**
     * {@code (mode, folded)}'s wavelet producer from the newest generation whose every producer the chain draws is
     * built (they draw together), null before the first; builds the current generation's.
     */
    public @Nullable Selection oitPipeline(InstanceType<?> type, Material material, boolean embedded, OitMode mode,
                                           int depthFormat, boolean folded) {
        var key = new OitGroupKey(OitKey.of(type, material, embedded), OitTransparency.additive(material),
                VkPrograms.get().localRead(), depthFormat);
        var entry = oit.computeIfAbsent(key, _ -> new ProgramVersions<>(VkAsyncBuilds.GROUP));
        var group = entry.select(ProgramGeneration.current().materials(), key,
                VkMeshVisualPipelines::requestOitGroup);
        return group == null ? null
                : new Selection((VkMeshPipeline) group[mode.ordinal() * 2 + (folded ? 1 : 0)].result(),
                entry.generation());
    }

    // Mirrors ShaderWarmup.warmVk: local read folds every mode, plus unfolded split instance bounds.
    private static VkAsyncBuilds.Build<?>[] requestOitGroup(OitGroupKey key) {
        var group = new VkAsyncBuilds.Build<?>[OitMode.values().length * 2];
        for (OitMode mode : OitMode.values()) {
            if (mode == OitMode.OFF) continue;
            for (boolean folded : new boolean[]{false, true}) {
                boolean drawn = key.localRead() ? folded || mode == OitMode.DEPTH_RANGE
                        && OitFramebuffer.SPLIT_INSTANCE_BOUNDS : !folded;
                if (drawn) {
                    group[mode.ordinal() * 2 + (folded ? 1 : 0)] = requestOit(key, mode, folded,
                            mode == OitMode.EVALUATE && key.additive());
                }
            }
        }
        return group;
    }

    private static VkAsyncBuilds.Build<?> requestOit(OitGroupKey group, OitMode mode, boolean folded,
                                                     boolean emission) {
        OitKey key = group.key();
        boolean bindless = bindless();
        String taskSource = MeshVisualShaders.assembleVkTask(key.mesh().type());
        String meshSource = meshSource(key.mesh(), key.debug(), bindless);
        String fsGl = MeshVisualShaders.assembleOitFragment(mode, key.light(), key.mesh().shaders().fragmentSource(),
                key.smoothness(), folded, fragmentExtra(key.mesh(), key.debug(), emission, bindless));
        List<VkDescriptorLayout.Binding> bindings = drawBindings(true, folded, key.light(), key.mesh().shaders(),
                bindless);
        #if MC >= 26.3
        List<VkGraphicsPipeline.Subpass> subpasses = folded || emission
                ? VkNativeOitPass.engineSubpasses(mode, emission) : List.of();
        #endif
        int depthFormat = group.depthFormat();
        return build(() -> {
            long task = 0;
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                task = compileTask(taskSource);
                mesh = compileMesh(meshSource, key.debug());
                frag = compileFragment(fsGl, "meshvisual_vk_oit_" + mode.name + (emission ? "_emission" : "")
                        + (folded ? "_folded" : "")
                        + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug().getSerializedName()));
                layout = new VkDescriptorLayout(bindings, PUSH_BYTES, TASK | MESH, bindless);
                VkMeshPipeline pipeline;
                if (folded) {
                    pipeline = new VkMeshPipeline(layout, task, mesh, frag, VkOitPipelines.FOLDED_FORMATS,
                            VkOitPipelines.foldedBlends(mode), key.compareOp(), key.cullMode(), depthFormat,
                            VkOitPipelines.foldedLocations(mode), VkOitPipelines.FOLDED_INPUT_INDICES,
                            key.biasConstant(), key.biasSlope());
                } else {
                    int[] colorFormats = switch (mode) {
                        case DEPTH_RANGE -> new int[]{VkOitPipelines.FMT_INSTANCE_BOUNDS};
                        case GENERATE_COEFFICIENTS -> new int[]{FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F, FMT_RGBA16F};
                        case EVALUATE -> new int[]{FMT_RGBA16F};
                        default -> throw new IllegalArgumentException("OitMode.OFF is not a producer mode");
                    };
                    VkGraphicsPipeline.Blend[] blends = new VkGraphicsPipeline.Blend[colorFormats.length];
                    Arrays.fill(blends,
                            mode == OitMode.DEPTH_RANGE ? VkGraphicsPipeline.max() : VkGraphicsPipeline.additive());
                    pipeline = new VkMeshPipeline(layout, task, mesh, frag, colorFormats, blends, key.compareOp(),
                            key.cullMode(), depthFormat, null, null, key.biasConstant(), key.biasSlope());
                }
                #if MC >= 26.3
                for (var subpass : subpasses) pipeline.subpassHandle(subpass);
                #endif
                return pipeline;
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(task, mesh, frag);
                if (t instanceof BackendUnavailableException failure) {
                    throw VkPrograms.tagged(folded ? ProgramAvailability.Feature.LOCAL_READ
                                    : bindless ? ProgramAvailability.Feature.BINDLESS : ProgramAvailability.Feature.MESH,
                            failure);
                }
                throw t;
            }
        });
    }

    /**
     * The newest built insert producer, null before the first; builds the current generation's.
     */
    public @Nullable Selection mlabPipeline(InstanceType<?> type, Material material, boolean embedded,
                                            OitInsertMode oitMode, int depthFormat) {
        return select(mlab, new MlabKey(OitKey.of(type, material, embedded), oitMode,
                        OitTransparency.additive(material), depthFormat), ProgramGeneration.current().materials(),
                VkMeshVisualPipelines::requestMlab);
    }

    private static VkAsyncBuilds.Build<?> requestMlab(MlabKey mlabKey) {
        OitKey key = mlabKey.key();
        OitInsertMode oitMode = mlabKey.oitMode();
        boolean emission = mlabKey.emission();
        boolean bindless = bindless();
        String taskSource = MeshVisualShaders.assembleVkTask(key.mesh().type());
        String meshSource = meshSource(key.mesh(), key.debug(), bindless);
        String fsGl = MeshVisualShaders.assembleMlabOitFragment(oitMode, key.light(),
                key.mesh().shaders().fragmentSource(), key.smoothness(),
                fragmentExtra(key.mesh(), key.debug(), emission, bindless));
        List<VkDescriptorLayout.Binding> bindings = new ArrayList<>(drawBindings(false, false, key.light(),
                key.mesh().shaders(), bindless));
        VkOitPipelines.mlabBindings(bindings, oitMode);
        #if MC >= 26.3
        List<VkGraphicsPipeline.Subpass> subpasses = VkNativeOitPass.engineSubpasses(oitMode);
        #endif
        return build(() -> {
            long task = 0;
            long mesh = 0;
            long frag = 0;
            VkDescriptorLayout layout = null;
            try {
                task = compileTask(taskSource);
                mesh = compileMesh(meshSource, key.debug());
                frag = compileFragment(fsGl, "meshvisual_vk_mlab_" + oitMode + (emission ? "_emission" : "")
                        + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug().getSerializedName()));
                layout = new VkDescriptorLayout(bindings, PUSH_BYTES, TASK | MESH, bindless);
                VkMeshPipeline pipeline = new VkMeshPipeline(layout, task, mesh, frag, new int[0],
                        new VkGraphicsPipeline.Blend[0], key.compareOp(), key.cullMode(), mlabKey.depthFormat(), null,
                        null, key.biasConstant(), key.biasSlope());
                #if MC >= 26.3
                for (var subpass : subpasses) pipeline.subpassHandle(subpass);
                #endif
                return pipeline;
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModules(task, mesh, frag);
                if (t instanceof BackendUnavailableException failure) {
                    throw VkPrograms.tagged(ProgramAvailability.insert(oitMode), failure);
                }
                throw t;
            }
        });
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
                if (t instanceof BackendUnavailableException failure) {
                    throw VkPrograms.tagged(ProgramAvailability.Feature.MESH, failure);
                }
                throw t;
            }
        }
        return builder;
    }

    /**
     * After {@link VkAsyncBuilds#join}.
     */
    public void delete() {
        solid.values().forEach(ProgramVersions::release);
        solid.clear();
        crumbling.values().forEach(ProgramVersions::release);
        crumbling.clear();
        oit.values().forEach(ProgramVersions::release);
        oit.clear();
        mlab.values().forEach(ProgramVersions::release);
        mlab.clear();
        if (builder != null) {
            builder.delete();
            builder = null;
        }
    }

    private record MeshKey(InstanceType<?> type, MaterialShaders shaders, boolean embedded) {
        static MeshKey of(InstanceType<?> type, Material material, boolean embedded) {
            return new MeshKey(type, material.shaders(), embedded);
        }
    }

    private record OitKey(MeshKey mesh, LightShader light, DepthTest depthTest, boolean cull, boolean polygonOffset,
                          DebugMode debug, LightSmoothness smoothness) {
        static OitKey of(InstanceType<?> type, Material material, boolean embedded) {
            return new OitKey(MeshKey.of(type, material, embedded), material.light(), material.depthTest(),
                    material.backfaceCulling(), material.polygonOffset(), FrameUniforms.debugMode(),
                    BackendConfig.INSTANCE.lightSmoothness());
        }

        int compareOp() {
            return VkGraphicsPipeline.compareOp(depthTest);
        }

        int cullMode() {
            return cull ? VK10.VK_CULL_MODE_BACK_BIT : VK10.VK_CULL_MODE_NONE;
        }

        float biasConstant() {
            return polygonOffset ? 10.0F : 0.0F;
        }

        float biasSlope() {
            return polygonOffset ? 1.0F : 0.0F;
        }
    }

    private record OitGroupKey(OitKey key, boolean additive, boolean localRead, int depthFormat) {
    }

    private record MlabKey(OitKey key, OitInsertMode oitMode, boolean emission, int depthFormat) {
    }

    private record SolidKey(MeshKey mesh, LightShader light, int colorFormat, int depthFormat,
                            Transparency transparency, DepthTest depthTest, boolean depthWrite, boolean colorWrite,
                            boolean cull, boolean polygonOffset, DebugMode debug, LightSmoothness smoothness) {
        static SolidKey of(InstanceType<?> type, Material material, boolean embedded, int colorFormat,
                           int depthFormat) {
            return new SolidKey(MeshKey.of(type, material, embedded), material.light(), colorFormat, depthFormat,
                    material.transparency(),
                    material.depthTest(), material.writeMask()
                                                  .depth(), material.writeMask()
                                                                    .color(), material.backfaceCulling(),
                    material.polygonOffset(), FrameUniforms.debugMode(), BackendConfig.INSTANCE.lightSmoothness());
        }
    }

    private record CrumblingKey(InstanceType<?> type, DebugMode debug, DepthTest depthTest, boolean cull,
                                LightSmoothness smoothness, int colorFormat, int depthFormat) {
    }
}
