package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.backend.BackendUnavailableException;
import dev.engine_room.flywheel.backend.compile.component.UberCullComponent;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.core.ShaderCache;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.gl.shader.ShaderType;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.util.AtomicReferenceCounted;
import dev.engine_room.flywheel.backend.vk.VkCaps;
import dev.engine_room.flywheel.backend.vk.VkContext;
import dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout;
import dev.engine_room.flywheel.backend.vk.shader.VkComputePipeline;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderCompiler;
import dev.engine_room.flywheel.backend.vk.shader.VkShaderTransform;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK12;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.*;

/**
 * Vulkan analogue of {@code IndirectPrograms}: the ref-counted root of every raw-VK pipeline. Owns the
 * instance-path compute (uber cull / apply / HiZ downsample); the graphics pipelines live in per-domain factories.
 */
public class VkPrograms extends AtomicReferenceCounted {
    public static final Consumer<Compilation> VK = ctx -> ctx.define("_FLW_VK");
    public static final Consumer<Compilation> LOCAL_READ = ctx -> ctx.define("_FLW_OIT_LOCAL_READ");
    public static final Consumer<Compilation> BINDLESS = ctx -> {
        ctx.requireExtension("GL_EXT_nonuniform_qualifier");
        ctx.define("_FLW_BINDLESS");
        ctx.define("_FLW_BINDLESS_CAPACITY", String.valueOf(VkCaps.BINDLESS_TABLE_CAPACITY));
    };
    private static final Identifier CULL_API_IMPL = ResourceUtil.rl("internal/indirect/cull_api_impl.glsl");
    private static final Identifier CULL_MAIN = ResourceUtil.rl("internal/indirect/cull.glsl");

    private static final Identifier APPLY_MAIN = ResourceUtil.rl("internal/indirect/apply.glsl");
    private static final Identifier DOWNSAMPLE_FIRST = ResourceUtil.rl("internal/indirect/downsample_first.glsl");
    private static final Identifier DOWNSAMPLE_SECOND = ResourceUtil.rl("internal/indirect/downsample_second.glsl");
    private static final Identifier DOWNSAMPLE_FIRST_SMALL = ResourceUtil.rl("internal/indirect/vk_downsample_first_small.glsl");
    private static final Identifier DOWNSAMPLE_SECOND_SMALL = ResourceUtil.rl("internal/indirect/vk_downsample_second_small.glsl");
    @Nullable
    private static VkPrograms instance;
    private static boolean activeBindlessTextures;

    public enum InstanceRoute {
        INDIRECT,
        DIRECT
    }

    public enum HiZRoute {
        MULTI_MIP,
        SINGLE_MIP,
        NONE
    }

    private final ShaderSources sources;
    private InstanceRoute instanceRoute;
    private final boolean drawIndex;
    private HiZRoute hiZRoute;
    private boolean gpuTerrainAccepted;
    private boolean gpuTranslucentTerrainAccepted;
    private final boolean bindlessTextures;
    private final boolean localRead;
    private final boolean descriptorBuffers;
    private final VkUberPipelines uber = new VkUberPipelines();
    private final VkOitPipelines oit = new VkOitPipelines();
    private final VkMeshVisualPipelines meshVisual = new VkMeshVisualPipelines();
    private final VkTerrainPrograms terrain;
    private final Map<Integer, VkComputePipeline> cullCache = new HashMap<>();
    private final Map<Integer, VkComputePipeline> cullPass2Cache = new HashMap<>();
    @Nullable
    private VkComputePipeline applyPipeline;
    @Nullable
    private VkComputePipeline downsampleFirstPipeline;
    @Nullable
    private VkComputePipeline downsampleSecondPipeline;
    @Nullable
    private VkComputePipeline downsampleFirstSmallPipeline;
    @Nullable
    private VkComputePipeline downsampleSecondSmallPipeline;

    private VkPrograms(ShaderSources sources) {
        this.sources = sources;
        this.terrain = new VkTerrainPrograms(sources);
        hiZRoute = VkCaps.MULTI_MIP_HIZ && ProgramAvailability.allows(ProgramAvailability.Feature.HIZ_MULTI)
                ? HiZRoute.MULTI_MIP
                : VkCaps.SINGLE_MIP_HIZ && ProgramAvailability.allows(ProgramAvailability.Feature.HIZ_SINGLE)
                        ? HiZRoute.SINGLE_MIP : HiZRoute.NONE;
        instanceRoute = VkCaps.GPU_INSTANCE_CULL && hiZRoute != HiZRoute.NONE
                && ProgramAvailability.allows(ProgramAvailability.Feature.INSTANCE_CULL)
                ? InstanceRoute.INDIRECT : InstanceRoute.DIRECT;
        drawIndex = ProgramAvailability.allows(ProgramAvailability.Feature.DRAW_INDEX);
        gpuTerrainAccepted = ProgramAvailability.allows(ProgramAvailability.Feature.OPAQUE_TERRAIN);
        gpuTranslucentTerrainAccepted = ProgramAvailability.allows(ProgramAvailability.Feature.TERRAIN_OIT);
        bindlessTextures = VkCaps.BINDLESS_TEXTURES_NEGOTIATED
                && ProgramAvailability.allows(ProgramAvailability.Feature.BINDLESS);
        localRead = VkCaps.DYNAMIC_RENDERING_LOCAL_READ_NEGOTIATED
                && ProgramAvailability.allows(ProgramAvailability.Feature.LOCAL_READ);
        descriptorBuffers = VkCaps.DESCRIPTOR_BUFFER_NEGOTIATED
                && ProgramAvailability.allows(ProgramAvailability.Feature.DESCRIPTOR_BUFFER);
    }

    public InstanceRoute instanceRoute() {
        return instanceRoute;
    }

    public boolean drawIndex() {
        return drawIndex && instanceRoute == InstanceRoute.INDIRECT;
    }

    public HiZRoute hiZRoute() {
        return hiZRoute;
    }

    public boolean bindlessTextures() {
        return bindlessTextures;
    }

    public static boolean bindlessTexturesEnabled() {
        return activeBindlessTextures;
    }

    public boolean localRead() {
        return localRead;
    }

    public boolean descriptorBuffers() {
        return descriptorBuffers;
    }

    public boolean rejectInstanceRoute() {
        if (instanceRoute == InstanceRoute.DIRECT) {
            return false;
        }
        instanceRoute = InstanceRoute.DIRECT;
        return true;
    }

    public boolean rejectHiZRoute() {
        if (hiZRoute == HiZRoute.NONE) {
            return false;
        }
        hiZRoute = hiZRoute == HiZRoute.MULTI_MIP && VkCaps.SINGLE_MIP_HIZ
                && ProgramAvailability.allows(ProgramAvailability.Feature.HIZ_SINGLE)
                ? HiZRoute.SINGLE_MIP : HiZRoute.NONE;
        if (hiZRoute == HiZRoute.NONE) {
            instanceRoute = InstanceRoute.DIRECT;
        }
        return true;
    }

    public boolean usesGpuTerrain() {
        return gpuTerrainAccepted && VkCaps.GPU_OPAQUE_TERRAIN && hiZRoute != HiZRoute.NONE;
    }

    public boolean usesGpuTranslucentTerrain() {
        return gpuTranslucentTerrainAccepted && VkCaps.GPU_TRANSLUCENT_CULL && hiZRoute != HiZRoute.NONE;
    }

    public boolean rejectGpuTerrain() {
        boolean accepted = gpuTerrainAccepted;
        gpuTerrainAccepted = false;
        return accepted;
    }

    public boolean rejectGpuTranslucentTerrain() {
        boolean accepted = gpuTranslucentTerrainAccepted;
        gpuTranslucentTerrainAccepted = false;
        return accepted;
    }

    public static void reload(ShaderSources sources) {
        setInstance(new VkPrograms(sources));
    }

    public static VkPrograms rejectFeature(ProgramAvailability.Feature feature) {
        VkPrograms old = Objects.requireNonNull(instance);
        switch (feature) {
            case INSTANCE_CULL -> old.rejectInstanceRoute();
            case HIZ_MULTI, HIZ_SINGLE -> old.rejectHiZRoute();
            case OPAQUE_TERRAIN -> old.rejectGpuTerrain();
            case TERRAIN_OIT -> old.rejectGpuTranslucentTerrain();
            default -> {
            }
        }
        VkPrograms replacement = new VkPrograms(old.sources);
        if (old.hiZRoute.ordinal() > replacement.hiZRoute.ordinal()) {
            replacement.hiZRoute = old.hiZRoute;
        }
        if (old.instanceRoute == InstanceRoute.DIRECT || replacement.hiZRoute == HiZRoute.NONE) {
            replacement.instanceRoute = InstanceRoute.DIRECT;
        }
        replacement.gpuTerrainAccepted &= old.gpuTerrainAccepted;
        replacement.gpuTranslucentTerrainAccepted &= old.gpuTranslucentTerrainAccepted;
        setInstance(replacement);
        return replacement;
    }

    static void setInstance(@Nullable VkPrograms newInstance) {
        if (instance != null) {
            instance.release();
        }
        if (newInstance != null) {
            newInstance.acquire();
        }
        instance = newInstance;
        activeBindlessTextures = newInstance != null && newInstance.bindlessTextures;
    }

    @Nullable
    public static VkPrograms get() {
        return instance;
    }

    public static boolean allLoaded() {
        return instance != null;
    }

    public static void kill() {
        setInstance(null);
    }

    static <T> T optional(ProgramAvailability.Feature feature, Supplier<T> factory) {
        try {
            return factory.get();
        } catch (ProgramAvailability.Failure failure) {
            throw failure;
        } catch (BackendUnavailableException failure) {
            throw tagged(feature, failure);
        }
    }

    static BackendUnavailableException tagged(ProgramAvailability.Feature feature, BackendUnavailableException cause) {
        return cause instanceof ProgramAvailability.Failure ? cause : new ProgramAvailability.Failure(feature, cause);
    }

    static <T> T bindless(Supplier<T> factory) {
        return activeBindlessTextures ? optional(ProgramAvailability.Feature.BINDLESS, factory) : factory.get();
    }

    private static void destroyModule(long module) {
        if (module != 0L) {
            VK12.vkDestroyShaderModule(VkContext.vkDevice(), module, null);
        }
    }

    static long compileCompute(String name, List<SourceComponent> roots, String... extraDefines) {
        Compilation c = new Compilation();
        c.version(GlslVersion.V460);
        for (String define : extraDefines) {
            c.define(define);
        }
        c.define(ShaderType.COMPUTE.define);
        c.define("_FLW_SUBGROUP_SIZE", Integer.toString(VkCaps.SUBGROUP_SIZE));
        c.define("_FLW_VK");
        if (VkCaps.SUBGROUP_BALLOT) {
            c.define("_FLW_HAS_SUBGROUP");
            c.requireExtension("GL_KHR_shader_subgroup_basic");
            c.requireExtension("GL_KHR_shader_subgroup_ballot");
        }
        ShaderCache.expand(roots, c::appendComponent);
        String vk = VkShaderTransform.toVulkan(c.assembledSource(), VkShaderTransform.Stage.COMPUTE);
        return VkShaderCompiler.compileModule(name, vk, VkShaderCompiler.KIND_COMPUTE);
    }

    private static List<Binding> cullBindings() {
        List<Binding> b = new ArrayList<>();
        b.add(new Binding(0, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(1, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(2, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(3, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(6, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(7, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(16, TYPE_UNIFORM_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(17, TYPE_UNIFORM_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(18, TYPE_UNIFORM_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(19, TYPE_UNIFORM_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_COMPUTE));
        return b;
    }

    private static List<Binding> applyBindings() {
        List<Binding> b = new ArrayList<>();
        b.add(new Binding(3, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        b.add(new Binding(4, TYPE_STORAGE_BUFFER, STAGE_COMPUTE));
        return b;
    }

    private static List<Binding> downsampleFirstBindings() {
        List<Binding> b = new ArrayList<>();
        b.add(new Binding(1, TYPE_STORAGE_IMAGE, STAGE_COMPUTE));
        b.add(new Binding(10, TYPE_COMBINED_IMAGE_SAMPLER, STAGE_COMPUTE));
        return b;
    }

    private static List<Binding> downsampleSecondBindings() {
        List<Binding> b = new ArrayList<>();
        for (int i = 0; i <= 6; i++) {
            b.add(new Binding(i, TYPE_STORAGE_IMAGE, STAGE_COMPUTE));
        }
        return b;
    }

    public VkUberPipelines uber() {
        return uber;
    }

    public VkOitPipelines oit() {
        return oit;
    }

    public VkMeshVisualPipelines meshVisual() {
        return meshVisual;
    }

    public VkTerrainPrograms terrain() {
        return terrain;
    }

    public VkComputePipeline cullPipeline() {
        var snapshot = InstanceTypeIds.snapshot();
        return cullCache.computeIfAbsent(snapshot.types().size(),
                $ -> optional(ProgramAvailability.Feature.INSTANCE_CULL, () -> buildCull(snapshot, false)));
    }

    public VkComputePipeline cullPass2Pipeline() {
        var snapshot = InstanceTypeIds.snapshot();
        return cullPass2Cache.computeIfAbsent(snapshot.types().size(),
                $ -> optional(ProgramAvailability.Feature.INSTANCE_CULL, () -> buildCull(snapshot, true)));
    }

    public VkComputePipeline applyPipeline() {
        if (applyPipeline == null) {
            applyPipeline = optional(ProgramAvailability.Feature.INSTANCE_CULL,
                    () -> buildComputePipeline("utilities/apply", APPLY_MAIN, applyBindings(), 0));
        }
        return applyPipeline;
    }

    public VkComputePipeline downsampleFirstPipeline() {
        if (hiZRoute == HiZRoute.SINGLE_MIP) {
            if (downsampleFirstSmallPipeline == null) {
                downsampleFirstSmallPipeline = optional(ProgramAvailability.Feature.HIZ_SINGLE,
                        () -> buildComputePipeline("hiz/downsample_first_small",
                                DOWNSAMPLE_FIRST_SMALL, downsampleFirstBindings(), 0));
            }
            return downsampleFirstSmallPipeline;
        }
        if (hiZRoute == HiZRoute.NONE) {
            throw new IllegalStateException("HiZ route is disabled");
        }
        if (downsampleFirstPipeline == null) {
            downsampleFirstPipeline = optional(ProgramAvailability.Feature.HIZ_MULTI,
                    () -> buildComputePipeline("hiz/downsample_first", DOWNSAMPLE_FIRST,
                            downsampleFirstBindings(), 0));
        }
        return downsampleFirstPipeline;
    }

    public VkComputePipeline downsampleSecondPipeline() {
        if (hiZRoute == HiZRoute.SINGLE_MIP) {
            if (downsampleSecondSmallPipeline == null) {
                downsampleSecondSmallPipeline = optional(ProgramAvailability.Feature.HIZ_SINGLE,
                        () -> buildComputePipeline("hiz/downsample_second_small",
                                DOWNSAMPLE_SECOND_SMALL, List.of(new Binding(0, TYPE_STORAGE_IMAGE, STAGE_COMPUTE),
                                        new Binding(1, TYPE_STORAGE_IMAGE, STAGE_COMPUTE)), 0));
            }
            return downsampleSecondSmallPipeline;
        }
        if (hiZRoute == HiZRoute.NONE) {
            throw new IllegalStateException("HiZ route is disabled");
        }
        if (downsampleSecondPipeline == null) {
            downsampleSecondPipeline = optional(ProgramAvailability.Feature.HIZ_MULTI,
                    () -> buildComputePipeline("hiz/downsample_second", DOWNSAMPLE_SECOND,
                            downsampleSecondBindings(), 8));
        }
        return downsampleSecondPipeline;
    }

    private VkComputePipeline buildComputePipeline(String name, Identifier source, List<Binding> bindings, int pushBytes) {
        long module = compileCompute(name, List.of(sources.get(source)));
        VkDescriptorLayout layout = null;
        try {
            layout = new VkDescriptorLayout(bindings, pushBytes, pushBytes == 0 ? 0 : STAGE_COMPUTE);
            return new VkComputePipeline(layout, module);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModule(module);
            throw t;
        }
    }

    private VkComputePipeline buildCull(InstanceTypeIds.Snapshot snapshot, boolean pass2) {
        List<SourceComponent> roots = List.of(
                sources.get(CULL_API_IMPL),
                new UberCullComponent(snapshot.types(), sources),
                sources.get(CULL_MAIN));
        String name = "culling/uber" + snapshot.types().size() + (pass2 ? "_pass2" : "");
        long module = compileCompute(name, roots, pass2 ? "_FLW_CULL_PASS2" : "_FLW_CULL_VIS_OUT");
        VkDescriptorLayout layout = null;
        try {
            layout = new VkDescriptorLayout(cullBindings(), 0, 0);
            return new VkComputePipeline(layout, module);
        } catch (Throwable t) {
            if (layout != null) {
                layout.delete();
            }
            destroyModule(module);
            throw t;
        }
    }

    @Override
    protected void _delete() {
        cullCache.values().forEach(VkComputePipeline::delete);
        cullCache.clear();
        cullPass2Cache.values().forEach(VkComputePipeline::delete);
        cullPass2Cache.clear();
        if (applyPipeline != null) {
            applyPipeline.delete();
            applyPipeline = null;
        }
        if (downsampleFirstPipeline != null) {
            downsampleFirstPipeline.delete();
            downsampleFirstPipeline = null;
        }
        if (downsampleSecondPipeline != null) {
            downsampleSecondPipeline.delete();
            downsampleSecondPipeline = null;
        }
        if (downsampleFirstSmallPipeline != null) {
            downsampleFirstSmallPipeline.delete();
            downsampleFirstSmallPipeline = null;
        }
        if (downsampleSecondSmallPipeline != null) {
            downsampleSecondSmallPipeline.delete();
            downsampleSecondSmallPipeline = null;
        }
        uber.delete();
        oit.delete();
        meshVisual.delete();
        terrain.delete();
    }
}
