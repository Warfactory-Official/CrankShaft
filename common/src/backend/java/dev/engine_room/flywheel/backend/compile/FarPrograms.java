package dev.engine_room.flywheel.backend.compile;

import com.google.common.collect.ImmutableList;
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

/**
 * Compute programs of far visuals: entry levels, impostor parts and bake passes, and over every instance type, row
 * culling, bounds and bricks.
 */
public final class FarPrograms extends AtomicReferenceCounted {
    private static final Identifier CULL_API_IMPL = ResourceUtil.rl("internal/indirect/cull_api_impl.glsl");
    private static final Identifier CULL = ResourceUtil.rl("internal/far/cull.glsl");
    private static final Identifier BOUNDS = ResourceUtil.rl("internal/far/bounds.glsl");
    private static final Identifier BRICKS = ResourceUtil.rl("internal/far/bricks.glsl");
    private static final Identifier ENTRIES = ResourceUtil.rl("internal/far/entries.glsl");
    private static final Identifier PARTS = ResourceUtil.rl("internal/far/parts.glsl");
    private static final Identifier BAKE_FINISH = ResourceUtil.rl("internal/far/bake_finish.glsl");
    private static final Identifier BAKE_MIP = ResourceUtil.rl("internal/far/bake_mip.glsl");
    private static final Identifier BAKE_COMPARE = ResourceUtil.rl("internal/far/bake_compare.glsl");
    private static final Identifier LAYER_SORT = ResourceUtil.rl("internal/far/layer_sort.glsl");
    private static final Identifier LAYER_MIP = ResourceUtil.rl("internal/far/layer_mip.glsl");
    private static final Identifier LAYER_COPY = ResourceUtil.rl("internal/far/layer_copy.glsl");
    private static final Identifier LAYER_COMPARE = ResourceUtil.rl("internal/far/layer_compare.glsl");
    private boolean layersRequested;
    private static final Compile<InstanceTypeIds.Snapshot> UBER = new Compile<>();
    private static final Compile<Identifier> PLAIN = new Compile<>();

    private static @Nullable FarPrograms instance;

    private final CompilationHarness<InstanceTypeIds.Snapshot> cull;
    private final CompilationHarness<InstanceTypeIds.Snapshot> bounds;
    private final CompilationHarness<InstanceTypeIds.Snapshot> bricks;
    private final CompilationHarness<Identifier> plain;
    private final ProgramVersions<InstanceTypeIds.Snapshot> versions = new ProgramVersions<>(
            new ProgramVersions.Tracker<>() {
                @Override
                public ProgramVersions.State state(InstanceTypeIds.Snapshot snapshot) {
                    if (cull.ready(snapshot) && bounds.ready(snapshot) && bricks.ready(snapshot))
                        return ProgramVersions.State.PUBLISHED;
                    return cull.pending(snapshot) || bounds.pending(snapshot) || bricks.pending(snapshot)
                            ? ProgramVersions.State.PENDING : ProgramVersions.State.ABSENT;
                }

                @Override
                public void release(InstanceTypeIds.Snapshot snapshot) {
                    cull.release(snapshot);
                    bounds.release(snapshot);
                    bricks.release(snapshot);
                }
            });
    private boolean plainRequested;

    private FarPrograms(ShaderSources sources) {
        cull = uber(sources, CULL, "far_cull");
        bounds = uber(sources, BOUNDS, "far_bounds");
        bricks = uber(sources, BRICKS, "far_bricks");
        plain = PLAIN.program()
                     .link(PLAIN.shader(GlCompat.MAX_GLSL_VERSION, ShaderType.COMPUTE)
                                .nameMapper(id -> "far/" + ResourceUtil.toDebugFileNameNoExtension(id))
                                .requireExtensions(extensions(GlCompat.MAX_GLSL_VERSION))
                                .onCompile((id, ctx) -> ctx.define("_FLW_FAR_NO_ROWS"))
                                .withResource(id -> id))
                     .postLink((id, program) -> Uniforms.setUniformBlockBindings(program))
                     .harness("far", sources);
    }

    static void reload(ShaderSources sources) {
        if (GlCompat.SUPPORTS_INDIRECT) {
            setInstance(new FarPrograms(sources));
        }
    }

    static void setInstance(@Nullable FarPrograms newInstance) {
        if (instance != null) {
            instance.release();
        }
        if (newInstance != null) {
            newInstance.acquire();
        }
        instance = newInstance;
    }

    public static @Nullable FarPrograms get() {
        return instance;
    }

    /**
     * Requests the current compute group and returns published coverage after the frame's compiler poll.
     */
    public InstanceTypeIds.@Nullable Snapshot prepare() {
        if (!plainRequested) {
            for (var id : List.of(ENTRIES, PARTS, BAKE_FINISH, BAKE_MIP, BAKE_COMPARE)) plain.request(id);
            plainRequested = true;
        }
        var current = InstanceTypeIds.snapshot();
        var selected = versions.select(new ProgramGeneration(current.types().size(), 0, 0), current, snapshot -> {
            cull.request(snapshot);
            bounds.request(snapshot);
            bricks.request(snapshot);
            return snapshot;
        });
        return plain.ready(ENTRIES) && plain.ready(PARTS) && plain.ready(BAKE_FINISH)
                && plain.ready(BAKE_MIP) && plain.ready(BAKE_COMPARE) ? selected : null;
    }

    public GlProgram cull(InstanceTypeIds.Snapshot snapshot) {
        return cull.get(snapshot);
    }

    public GlProgram bounds(InstanceTypeIds.Snapshot snapshot) {
        return bounds.get(snapshot);
    }

    public GlProgram bricks(InstanceTypeIds.Snapshot snapshot) {
        return bricks.get(snapshot);
    }

    public GlProgram entries() {
        return plain.get(ENTRIES);
    }

    public GlProgram parts() {
        return plain.get(PARTS);
    }

    public GlProgram bakeFinish() {
        return plain.get(BAKE_FINISH);
    }

    public GlProgram bakeMip() {
        return plain.get(BAKE_MIP);
    }

    public GlProgram bakeCompare() {
        return plain.get(BAKE_COMPARE);
    }

    /**
     * Requests the raw layered bake group without making opaque users wait for it. Render thread.
     */
    public boolean prepareLayers() {
        if (!layersRequested) {
            for (var id : List.of(LAYER_SORT, LAYER_MIP, LAYER_COPY, LAYER_COMPARE)) plain.request(id);
            layersRequested = true;
        }
        return plain.ready(LAYER_SORT) && plain.ready(LAYER_MIP) && plain.ready(LAYER_COPY)
                && plain.ready(LAYER_COMPARE);
    }

    public GlProgram layerSort() {
        return plain.get(LAYER_SORT);
    }

    public GlProgram layerMip() {
        return plain.get(LAYER_MIP);
    }

    public GlProgram layerCompare() {
        return plain.get(LAYER_COMPARE);
    }

    public GlProgram layerCopy() {
        return plain.get(LAYER_COPY);
    }

    @Override
    protected void _delete() {
        versions.release();
        cull.delete();
        bounds.delete();
        bricks.delete();
        plain.delete();
    }

    private static CompilationHarness<InstanceTypeIds.Snapshot> uber(ShaderSources sources, Identifier main,
                                                                     String name) {
        return UBER.program()
                   .link(UBER.shader(GlCompat.MAX_GLSL_VERSION, ShaderType.COMPUTE)
                             .nameMapper(snapshot -> "far/" + name + snapshot.types().size())
                             .requireExtensions(extensions(GlCompat.MAX_GLSL_VERSION))
                             .withResource(CULL_API_IMPL)
                             .with((snapshot, loader) -> new UberCullComponent(snapshot.types(), loader))
                             .withResource(main))
                   .postLink((key, program) -> Uniforms.setUniformBlockBindings(program))
                   .harness(name, sources);
    }

    private static List<String> extensions(GlslVersion version) {
        var extensions = ImmutableList.<String>builder();
        if (version.compareTo(GlslVersion.V420) < 0) {
            extensions.add("GL_ARB_shading_language_420pack");
        }
        if (version.compareTo(GlslVersion.V430) < 0) {
            extensions.add("GL_ARB_shader_storage_buffer_object");
            extensions.add("GL_ARB_compute_shader");
        }
        return extensions.build();
    }
}
