package dev.engine_room.flywheel.backend.compile;

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
import java.util.function.Consumer;

import static dev.engine_room.flywheel.backend.vk.descriptor.VkDescriptorLayout.*;

/**
 * Vulkan analogue of {@code IndirectPrograms}: the ref-counted root of every raw-VK pipeline. Owns the
 * instance-path compute (uber cull / apply / HiZ downsample); the graphics pipelines live in per-domain factories.
 */
public class VkPrograms extends AtomicReferenceCounted {
    // _FLW_VK selects the relocated-binding branches (u_RegionChunkOrigin -> 23, _flw_SectionFadeVis -> SSBO 1) the transform expects on Vulkan.
    public static final Consumer<Compilation> VK = ctx -> ctx.define("_FLW_VK");
    // Folded OIT (dynamic_rendering_local_read): the wavelet reads come from input attachments.
    public static final Consumer<Compilation> LOCAL_READ = ctx -> ctx.define("_FLW_OIT_LOCAL_READ");
    // Bindless textures: the define selects the _FLW_BINDLESS blocks; nonuniformEXT needs its extension. The
    // negotiated capacity flows in as a define so the shader array and the set-1 layout stay in lockstep.
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
    @Nullable
    private static VkPrograms instance;

    private final ShaderSources sources;
    private final VkUberPipelines uber = new VkUberPipelines();
    private final VkOitPipelines oit = new VkOitPipelines();
    // Shared across engines, so the reload-time warm-up populates the SAME instance the draw managers read.
    private final VkMeshVisualPipelines meshVisual = new VkMeshVisualPipelines();
    private final VkTerrainPrograms terrain;
    // Uber cull, keyed by the registered-type count: one program covers every type; a grown registry keys a fresh compile.
    private final Map<Integer, VkComputePipeline> cullCache = new HashMap<>();
    private final Map<Integer, VkComputePipeline> cullPass2Cache = new HashMap<>();
    @Nullable
    private VkComputePipeline applyPipeline;
    @Nullable
    private VkComputePipeline downsampleFirstPipeline;
    @Nullable
    private VkComputePipeline downsampleSecondPipeline;

    private VkPrograms(ShaderSources sources) {
        this.sources = sources;
        this.terrain = new VkTerrainPrograms(sources);
    }

    public static void reload(ShaderSources sources) {
        setInstance(new VkPrograms(sources));
    }

    static void setInstance(@Nullable VkPrograms newInstance) {
        if (instance != null) {
            instance.release();
        }
        if (newInstance != null) {
            newInstance.acquire();
        }
        instance = newInstance;
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

    private static void destroyModule(long module) {
        if (module != 0L) {
            VK12.vkDestroyShaderModule(VkContext.vkDevice(), module, null);
        }
    }

    static long compileCompute(String name, List<SourceComponent> roots, String... extraDefines) {
        Compilation c = new Compilation();
        c.version(GlslVersion.V460);
        // MUST follow version(): Compilation appends in call order and #version must be the first line.
        for (String define : extraDefines) {
            c.define(define);
        }
        c.define(ShaderType.COMPUTE.define);
        // apply.glsl's local_size_x; matches the device wave width. The dispatch count in
        // VkIndirectDrawManager#dispatchApply MUST divide by the same value or draws are dropped / redundant workgroups launched.
        c.define("_FLW_SUBGROUP_SIZE", Integer.toString(VkCaps.SUBGROUP_SIZE));
        // Selects the descriptor-SSBO buffer-access variant in the terrain cull shaders (vs GL's NV-bindless scene UBO).
        c.define("_FLW_VK");
        // cull.glsl's subgroup-coalesced append, gated on the queried BASIC+BALLOT compute support; `require` so an
        // unsupported builtin fails shaderc loudly instead of half-compiling.
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
        return cullCache.computeIfAbsent(snapshot.types().size(), $ -> buildCull(snapshot, false));
    }

    public VkComputePipeline cullPass2Pipeline() {
        var snapshot = InstanceTypeIds.snapshot();
        return cullPass2Cache.computeIfAbsent(snapshot.types().size(), $ -> buildCull(snapshot, true));
    }

    public VkComputePipeline applyPipeline() {
        if (applyPipeline == null) {
            long module = compileCompute("utilities/apply", List.of(sources.get(APPLY_MAIN)));
            VkDescriptorLayout layout = null;
            try {
                layout = new VkDescriptorLayout(applyBindings(), 0, 0);
                applyPipeline = new VkComputePipeline(layout, module);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModule(module);
                throw t;
            }
        }
        return applyPipeline;
    }

    public VkComputePipeline downsampleFirstPipeline() {
        if (downsampleFirstPipeline == null) {
            long module = compileCompute("hiz/downsample_first", List.of(sources.get(DOWNSAMPLE_FIRST)));
            VkDescriptorLayout layout = null;
            try {
                layout = new VkDescriptorLayout(downsampleFirstBindings(), 0, 0);
                downsampleFirstPipeline = new VkComputePipeline(layout, module);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModule(module);
                throw t;
            }
        }
        return downsampleFirstPipeline;
    }

    public VkComputePipeline downsampleSecondPipeline() {
        if (downsampleSecondPipeline == null) {
            long module = compileCompute("hiz/downsample_second", List.of(sources.get(DOWNSAMPLE_SECOND)));
            VkDescriptorLayout layout = null;
            try {
                // 8-byte push range (mip_levels, base_mip_level) -- VkShaderTransform folds the shader's bare uniforms into a push_constant block.
                layout = new VkDescriptorLayout(downsampleSecondBindings(), 8, STAGE_COMPUTE);
                downsampleSecondPipeline = new VkComputePipeline(layout, module);
            } catch (Throwable t) {
                if (layout != null) {
                    layout.delete();
                }
                destroyModule(module);
                throw t;
            }
        }
        return downsampleSecondPipeline;
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
        uber.delete();
        oit.delete();
        meshVisual.delete();
        terrain.delete();
    }
}
