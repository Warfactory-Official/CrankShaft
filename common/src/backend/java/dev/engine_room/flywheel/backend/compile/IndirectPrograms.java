package dev.engine_room.flywheel.backend.compile;

import com.google.common.collect.ImmutableList;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compile.component.UberCullComponent;
import dev.engine_room.flywheel.backend.compile.core.CompilationHarness;
import dev.engine_room.flywheel.backend.compile.core.Compile;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.backend.gl.shader.ShaderType;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.util.AtomicReferenceCounted;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

public class IndirectPrograms extends AtomicReferenceCounted {
    private static final Identifier CULL_SHADER_API_IMPL = ResourceUtil.rl("internal/indirect/cull_api_impl.glsl");
    private static final Identifier CULL_SHADER_MAIN = ResourceUtil.rl("internal/indirect/cull.glsl");
    private static final Identifier APPLY_SHADER_MAIN = ResourceUtil.rl("internal/indirect/apply.glsl");
    private static final Identifier SCATTER_SHADER_MAIN = ResourceUtil.rl("internal/indirect/scatter.glsl");
    private static final Identifier DOWNSAMPLE_FIRST = ResourceUtil.rl("internal/indirect/downsample_first.glsl");
    private static final Identifier DOWNSAMPLE_SECOND = ResourceUtil.rl("internal/indirect/downsample_second.glsl");
    private static final Identifier TERRAIN_REGION_TEST = ResourceUtil.rl("internal/indirect/terrain_region_test.comp");
    private static final Identifier TERRAIN_SECTION_TEST = ResourceUtil.rl(
            "internal/indirect/terrain_section_test.comp");
    private static final Identifier TERRAIN_COMMAND_BUILDER = ResourceUtil.rl(
            "internal/indirect/terrain_command_builder.comp");
    private static final Identifier TERRAIN_TRANSLUCENT_CULL_BUILD = ResourceUtil.rl(
            "internal/indirect/terrain_translucent_cull_build.comp");

    // The terrain HiZ-cull comps deref NV-bindless device pointers from the scene UBO on GL; VK compiles the same
    // bodies with descriptor SSBOs and no NV extensions. The GL-only extensions are declared here: a conditional
    // #extension can't ride a body.
    private static final Set<Identifier> NV_BINDLESS_UTILS = Set.of(TERRAIN_REGION_TEST, TERRAIN_SECTION_TEST,
            TERRAIN_COMMAND_BUILDER, TERRAIN_TRANSLUCENT_CULL_BUILD);

    private static final Compile<InstanceTypeIds.Snapshot> CULL = new Compile<>();
    private static final Compile<Identifier> UTIL = new Compile<>();

    private static final List<String> EXTENSIONS = getExtensions(GlCompat.MAX_GLSL_VERSION);
    private static final List<String> COMPUTE_EXTENSIONS = getComputeExtensions(GlCompat.MAX_GLSL_VERSION);

    @Nullable
    private static IndirectPrograms instance;

    private final CompilationHarness<InstanceTypeIds.Snapshot> culling;
    private final CompilationHarness<InstanceTypeIds.Snapshot> cullingPass2;
    private final CompilationHarness<Identifier> utils;

    private IndirectPrograms(CompilationHarness<InstanceTypeIds.Snapshot> culling,
                             CompilationHarness<InstanceTypeIds.Snapshot> cullingPass2,
                             CompilationHarness<Identifier> utils) {
        this.culling = culling;
        this.cullingPass2 = cullingPass2;
        this.utils = utils;
    }

    private static List<String> getExtensions(GlslVersion glslVersion) {
        var extensions = ImmutableList.<String>builder();
        if (glslVersion.compareTo(GlslVersion.V400) < 0) {
            extensions.add("GL_ARB_gpu_shader5");
        }
        if (glslVersion.compareTo(GlslVersion.V420) < 0) {
            extensions.add("GL_ARB_shading_language_420pack");
            extensions.add("GL_ARB_shader_image_load_store");
        }
        if (glslVersion.compareTo(GlslVersion.V430) < 0) {
            extensions.add("GL_ARB_shader_storage_buffer_object");
            extensions.add("GL_ARB_shader_image_size");
        }
        if (glslVersion.compareTo(GlslVersion.V460) < 0) {
            extensions.add("GL_ARB_shader_draw_parameters");
        }
        return extensions.build();
    }

    private static List<String> getComputeExtensions(GlslVersion glslVersion) {
        var extensions = ImmutableList.<String>builder();

        extensions.addAll(EXTENSIONS);

        if (glslVersion.compareTo(GlslVersion.V430) < 0) {
            extensions.add("GL_ARB_compute_shader");
        }
        return extensions.build();
    }

    static void reload(ShaderSources sources) {
        if (!GlCompat.SUPPORTS_INDIRECT) {
            FlwBackend.LOGGER.warn("[indirect] reload skipped: SUPPORTS_INDIRECT=false");
            return;
        }

        try {
            var cullingCompiler = createCullingCompiler(sources, false);
            var cullingPass2Compiler = createCullingCompiler(sources, true);
            var utilCompiler = createUtilCompiler(sources);

            IndirectPrograms newInstance = new IndirectPrograms(cullingCompiler, cullingPass2Compiler, utilCompiler);

            setInstance(newInstance);
            FlwBackend.LOGGER.info("[indirect] reload complete: allLoaded={} | IndirectPrograms.CL={} id={}",
                    allLoaded(),
                    IndirectPrograms.class.getClassLoader(),
                    Integer.toHexString(System.identityHashCode(IndirectPrograms.class)));
        } catch (Throwable t) {
            FlwBackend.LOGGER.error("[indirect] reload FAILED (instance left null)", t);
        }
    }

    private static CompilationHarness<InstanceTypeIds.Snapshot> createCullingCompiler(ShaderSources sources,
                                                                                      boolean pass2) {
        return CULL.program()
                   .link(CULL.shader(GlCompat.MAX_GLSL_VERSION, ShaderType.COMPUTE)
                             .nameMapper(snapshot -> "culling/uber" + snapshot.types().size() + (pass2 ? "_pass2" : ""))
                             .requireExtensions(COMPUTE_EXTENSIONS)
                             .define("_FLW_SUBGROUP_SIZE", GlCompat.SUBGROUP_SIZE)
                             .onCompile(($, ctx) -> {
                                 ctx.define(pass2 ? "_FLW_CULL_PASS2" : "_FLW_CULL_VIS_OUT");
                                 if (GlCompat.CAPABILITIES.GL_KHR_shader_subgroup) {
                                     ctx.define("_FLW_HAS_SUBGROUP");
                                     ctx.requireExtension("GL_KHR_shader_subgroup_basic");
                                     ctx.requireExtension("GL_KHR_shader_subgroup_ballot");
                                 }
                             })
                             .withResource(CULL_SHADER_API_IMPL)
                             .with((snapshot, loader) -> new UberCullComponent(snapshot.types(), loader))
                             .withResource(CULL_SHADER_MAIN))
                   .postLink((key, program) -> Uniforms.setUniformBlockBindings(program))
                   .harness(pass2 ? "cullingPass2" : "culling", sources);
    }

    /**
     * A compiler for utility shaders, directly compiles the shader at the resource location specified by the parameter.
     */
    private static CompilationHarness<Identifier> createUtilCompiler(ShaderSources sources) {
        return UTIL.program()
                   .link(UTIL.shader(GlCompat.MAX_GLSL_VERSION, ShaderType.COMPUTE)
                             .nameMapper(resourceLocation -> "utilities/" + ResourceUtil.toDebugFileNameNoExtension(
                                     resourceLocation))
                             .requireExtensions(COMPUTE_EXTENSIONS)
                             .define("_FLW_SUBGROUP_SIZE", GlCompat.SUBGROUP_SIZE)
                             .onCompile((id, ctx) -> {
                                 if (NV_BINDLESS_UTILS.contains(id)) {
                                     ctx.requireExtension("GL_NV_gpu_shader5");
                                     ctx.requireExtension("GL_NV_shader_buffer_load");
                                 }
                             })
                             .withResource(s -> s))
                   .harness("utilities", sources);
    }

    static void setInstance(@Nullable IndirectPrograms newInstance) {
        if (instance != null) {
            instance.release();
        }
        if (newInstance != null) {
            newInstance.acquire();
        }
        instance = newInstance;
    }

    @Nullable
    public static IndirectPrograms get() {
        return instance;
    }

    public static boolean allLoaded() {
        return instance != null;
    }

    public static void kill() {
        setInstance(null);
    }

    public GlProgram getCullingProgram() {
        return culling.get(InstanceTypeIds.snapshot());
    }

    public GlProgram getCullingPass2Program() {
        return cullingPass2.get(InstanceTypeIds.snapshot());
    }

    public GlProgram getApplyProgram() {
        return utils.get(APPLY_SHADER_MAIN);
    }

    public GlProgram getScatterProgram() {
        return utils.get(SCATTER_SHADER_MAIN);
    }

    public GlProgram getDownsampleFirstProgram() {
        return utils.get(DOWNSAMPLE_FIRST);
    }

    public GlProgram getDownsampleSecondProgram() {
        return utils.get(DOWNSAMPLE_SECOND);
    }

    public GlProgram getTerrainRegionTestProgram() {
        return utils.get(TERRAIN_REGION_TEST);
    }

    public GlProgram getTerrainSectionTestProgram() {
        return utils.get(TERRAIN_SECTION_TEST);
    }

    public GlProgram getTerrainCommandBuilderProgram() {
        return utils.get(TERRAIN_COMMAND_BUILDER);
    }

    public GlProgram getTerrainTranslucentCullBuildProgram() {
        return utils.get(TERRAIN_TRANSLUCENT_CULL_BUILD);
    }

    @Override
    protected void _delete() {
        culling.delete();
        cullingPass2.delete();
        utils.delete();
    }
}
