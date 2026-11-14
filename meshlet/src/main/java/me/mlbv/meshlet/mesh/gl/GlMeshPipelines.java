// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock
package me.mlbv.meshlet.mesh.gl;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.*;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainOverlayDraw;
import dev.engine_room.flywheel.backend.gl.shader.MeshGlPrograms;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import me.mlbv.meshlet.mesh.shared.MeshShaderPrep;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.NVMeshShader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

// Each program is assembled through flywheel's shared Compilation pipeline, then compiled/linked with the raw-GL
// pattern that mirrors the working MeshHelloTriangle (driver log surfaced verbatim on failure), multi-stage NV.
public final class GlMeshPipelines {
    private int terrainProgramSolid = 0;
    private int terrainProgramCutout = 0;
    private int commandBuilderProgram = 0;
    private boolean builderFailed = false;
    private int lastOpaqueConfigKey = -1;
    private List<Identifier> lastOverlaySources = List.of();
    private final Map<OverlayKey, int[]> opaqueVariants = new HashMap<>();
    private int translucentBuilderProgram = 0;
    private int translucentGatherProgram = 0;
    private int translucentProgramDepthRange = 0;
    private int translucentProgramCoeffs = 0;
    private int translucentProgramEvaluate = 0;
    private boolean translucentFailed = false;
    private boolean translucentWaveletFailed = false;
    private final int[] translucentMlabPrograms = new int[OitInsertMode.values().length];
    private boolean translucentMlabFailed = false;
    private TranslucentKey translucentKey, translucentMlabKey;
    private final Map<TranslucentKey, int[]> translucentVariants = new HashMap<>(), translucentMlabVariants = new HashMap<>();

    public GlMeshPipelines() {
    }

    private static Consumer<Compilation> translucentMlabFragDefines(OitInsertMode mode, boolean linear) {
        return ctx -> {
            RenderPassShaders.mlabProducerDefines(ctx, mode);
            if (linear) {
                ctx.define(TerrainAtlasFilter.LINEAR_DEFINE);
            }
        };
    }

    private static int compileShader(int glType, String path) {
        return compileShader(glType, path, _ -> {
        });
    }

    // Assemble via flywheel's Compilation pipeline: #version + config-global defines + this call's extras, then
    // meshlet's include-resolved body (its own #version stripped -- Compilation emits it).
    private static int compileShader(int glType, String path, Consumer<Compilation> extraDefines) {
        return compileShader(glType, path, extraDefines, List.of());
    }

    private static int compileShader(int glType, String path, Consumer<Compilation> extraDefines,
                                     List<SourceComponent> tail) {
        var roots = new ArrayList<SourceComponent>();
        var overlays = TerrainOverlayDraw.sources();
        boolean overlay = path.startsWith("terrain/gl/translucent_") && !overlays.isEmpty();
        if (overlay && glType == GL20C.GL_FRAGMENT_SHADER)
            roots.addAll(TerrainOverlayPipelines.prelude(overlays, "0.1"));
        roots.add(FlwPrograms.SOURCES.get(meshletId(path)));
        roots.addAll(tail);
        String src = ShaderAssembly.assemble(ctx -> {
            MeshShaderPrep.applyGlobalDefines(ctx);
            if (overlay) {
                ctx.define("_FLW_TERRAIN_OVERLAY");
                ctx.define("_FLW_TERRAIN_RECEIVER_DATA");
                ctx.define("_FLW_TERRAIN_EXPLICIT_VARYINGS");
            }
            extraDefines.accept(ctx);
        }, roots);
        return MeshGlPrograms.compileShader("gl_mesh_shader", glType, path, src);
    }

    private static Identifier meshletId(String path) {
        return Identifier.fromNamespaceAndPath("meshlet", path);
    }

    private static Consumer<Compilation> translucentFragDefines(OitMode mode, boolean linear) {
        return ctx -> {
            ctx.define(mode.define);
            if (OitConfig.coefficientArray()) {
                ctx.define("_FLW_COEFF_ARRAY");
            }
            if (linear) {
                ctx.define(TerrainAtlasFilter.LINEAR_DEFINE);
            }
        };
    }

    private static void deleteIfPresent(int... shaders) {
        for (int s : shaders) {
            if (s != 0) {
                GL20C.glDeleteShader(s);
            }
        }
    }

    private static void deleteProgramIfPresent(int... programs) {
        for (int p : programs) {
            if (p != 0) {
                GL20C.glDeleteProgram(p);
            }
        }
    }

    public void warmUp() {
        var terrain = BackendConfig.INSTANCE.terrainMode();
        if (terrain.ownsOpaque()) ensureCompiled(MeshFeatureConfig.currentKey());
        if (terrain.compositesTranslucent()) warmTranslucent();
    }

    public void warmTranslucent() {
        boolean linear = TerrainAtlasFilter.linear();
        OitInsertMode mode = OitConfig.resolveInsertMode();
        if (mode == null) {
            ensureTranslucentCompiled(linear);
        } else if (ensureTranslucentInfrastructure(linear)) {
            ensureTranslucentMlabCompiled(mode, linear);
        }
    }

    public int terrainProgram(boolean cutout) {
        return cutout ? terrainProgramCutout : terrainProgramSolid;
    }

    public int commandBuilderProgram() {
        return commandBuilderProgram;
    }

    public boolean ensureCompiled(int configKey) {
        if (commandBuilderProgram == 0 && !builderFailed) {
            int comp = compileShader(GL43C.GL_COMPUTE_SHADER, "terrain/gl/command_buffer_builder.comp");
            int builder = comp != 0 ? MeshGlPrograms.linkProgram("gl_mesh_shader", "command_builder", comp) : 0;
            deleteIfPresent(comp);
            if (builder == 0) {
                builderFailed = true;
            } else {
                commandBuilderProgram = builder;
            }
        }
        var overlays = TerrainOverlayDraw.sources();
        if (configKey != lastOpaqueConfigKey || !overlays.equals(lastOverlaySources)) {
            // First call or a video-settings change: rebuild with this key's #define block. A failing config
            // compiles ONCE (key recorded); flipping keys retries, so a buggy variant never strands a good one.
            lastOpaqueConfigKey = configKey;
            lastOverlaySources = overlays;
            recompileTerrainProgram(configKey);
        }
        return terrainProgramSolid != 0 && terrainProgramCutout != 0 && commandBuilderProgram != 0;
    }

    private void recompileTerrainProgram(int configKey) {
        var key = new OverlayKey(configKey, lastOverlaySources);
        var cached = opaqueVariants.get(key);
        if (cached != null) {
            terrainProgramSolid = cached[0];
            terrainProgramCutout = cached[1];
            return;
        }
        terrainProgramSolid = terrainProgramCutout = 0;
        Consumer<Compilation> features = ctx -> MeshFeatureConfig.applyFeatureDefines(ctx, configKey);
        if (!lastOverlaySources.isEmpty()) features = features.andThen(ctx -> {
            ctx.define("_FLW_TERRAIN_OVERLAY");
            ctx.define("_FLW_TERRAIN_EXPLICIT_VARYINGS");
        });
        Consumer<Compilation> fragExtras = features.andThen(
                ctx -> MeshFeatureConfig.applyFragExtensions(ctx, configKey));
        var tail = lastOverlaySources.isEmpty() ? List.<SourceComponent>of() : TerrainOverlayPipelines.tail(
                lastOverlaySources);
        if (!tail.isEmpty()) fragExtras = fragExtras.andThen(ctx -> {
            ctx.define("main", "_flw_terrainBase");
            ctx.define("_FLW_TERRAIN_BLOCK_INPUT");
            ctx.define("_flw_terrainPosition", "v_in.terrainPosition");
            ctx.define("_flw_terrainReceiver", "v_in.terrainReceiver");
        });
        int task = compileShader(NVMeshShader.GL_TASK_SHADER_NV, "terrain/gl/task.task", features);
        int mesh = compileShader(NVMeshShader.GL_MESH_SHADER_NV, "terrain/gl/mesh.mesh", features);
        int fragSolid = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/frag.frag",
                fragExtras.andThen(ctx -> ctx.define("MESHLET_SOLID_PASS")), tail);
        int fragCutout = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/frag.frag", fragExtras, tail);
        if (task == 0 || mesh == 0 || fragSolid == 0 || fragCutout == 0) {
            deleteIfPresent(task, mesh, fragSolid, fragCutout);
            return;
        }
        int solid = MeshGlPrograms.linkProgram("gl_mesh_shader", "terrain_solid", task, mesh, fragSolid);
        int cutout = MeshGlPrograms.linkProgram("gl_mesh_shader", "terrain_cutout", task, mesh, fragCutout);
        deleteIfPresent(task, mesh, fragSolid, fragCutout);
        if (solid == 0 || cutout == 0) {
            deleteProgramIfPresent(solid, cutout);
            return;
        }
        terrainProgramSolid = solid;
        terrainProgramCutout = cutout;
        opaqueVariants.put(key, new int[]{solid, cutout});
        FlwBackend.LOGGER.info("[gl_mesh_shader] terrain programs linked (solid={} cutout={} configKey={})",
                solid, cutout, configKey);
    }

    public int translucentBuilderProgram() {
        return translucentBuilderProgram;
    }

    public int translucentGatherProgram() {
        return translucentGatherProgram;
    }

    public int translucentProgram(OitMode mode) {
        return switch (mode) {
            case DEPTH_RANGE -> translucentProgramDepthRange;
            case GENERATE_COEFFICIENTS -> translucentProgramCoeffs;
            case EVALUATE -> translucentProgramEvaluate;
            default -> 0;
        };
    }

    public boolean ensureTranslucentInfrastructure(boolean linear) {
        var sources = TerrainOverlayDraw.sources();
        if (translucentKey == null || translucentKey.linear() != linear || !translucentKey.sources().equals(sources)) {
            translucentKey = new TranslucentKey(linear, sources);
            translucentProgramDepthRange = 0;
            translucentProgramCoeffs = 0;
            translucentProgramEvaluate = 0;
            translucentBuilderProgram = 0;
            translucentGatherProgram = 0;
            translucentFailed = false;
            translucentWaveletFailed = false;
            var cached = translucentVariants.get(translucentKey);
            if (cached != null) {
                translucentProgramDepthRange = cached[0];
                translucentProgramCoeffs = cached[1];
                translucentProgramEvaluate = cached[2];
                translucentBuilderProgram = cached[3];
                translucentGatherProgram = cached[4];
            }
        }
        if (translucentFailed) {
            return false;
        }
        if (translucentBuilderProgram != 0 && translucentGatherProgram != 0) return true;

        int gather = compileShader(GL43C.GL_COMPUTE_SHADER, "terrain/gl/translucent_gather.comp");
        int comp = compileShader(GL43C.GL_COMPUTE_SHADER, "terrain/gl/translucent_command_buffer_builder.comp");
        if (gather == 0 || comp == 0) {
            deleteIfPresent(gather, comp);
            translucentFailed = true;
            return false;
        }
        int builder = MeshGlPrograms.linkProgram("gl_mesh_shader", "translucent_command_builder", comp);
        int gatherProg = MeshGlPrograms.linkProgram("gl_mesh_shader", "translucent_gather", gather);
        deleteIfPresent(gather, comp);
        if (builder == 0 || gatherProg == 0) {
            deleteProgramIfPresent(builder, gatherProg);
            translucentFailed = true;
            return false;
        }
        translucentBuilderProgram = builder;
        translucentGatherProgram = gatherProg;
        var cached = translucentVariants.computeIfAbsent(translucentKey, _ -> new int[5]);
        cached[3] = builder;
        cached[4] = gatherProg;
        return true;
    }

    public boolean ensureTranslucentCompiled(boolean linear) {
        if (!ensureTranslucentInfrastructure(linear) || translucentWaveletFailed) return false;
        if (translucentProgramDepthRange != 0 && translucentProgramCoeffs != 0
                && translucentProgramEvaluate != 0) return true;

        int vert = compileShader(GL20C.GL_VERTEX_SHADER, "terrain/gl/translucent_pull.vert");
        int fragDepthRange = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/translucent_frag.frag",
                translucentFragDefines(OitMode.DEPTH_RANGE, linear));
        int fragCoeffs = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/translucent_frag.frag",
                translucentFragDefines(OitMode.GENERATE_COEFFICIENTS, linear));
        int fragEvaluate = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/translucent_frag.frag",
                translucentFragDefines(OitMode.EVALUATE, linear));

        if (vert == 0 || fragDepthRange == 0 || fragCoeffs == 0 || fragEvaluate == 0) {
            deleteIfPresent(vert, fragDepthRange, fragCoeffs, fragEvaluate);
            translucentWaveletFailed = true;
            return false;
        }

        int progDepthRange = MeshGlPrograms.linkProgram("gl_mesh_shader", "translucent_depth_range", vert,
                fragDepthRange);
        int progCoeffs = MeshGlPrograms.linkProgram("gl_mesh_shader", "translucent_coefficients", vert, fragCoeffs);
        int progEvaluate = MeshGlPrograms.linkProgram("gl_mesh_shader", "translucent_evaluate", vert, fragEvaluate);
        deleteIfPresent(vert, fragDepthRange, fragCoeffs, fragEvaluate);

        if (progDepthRange == 0 || progCoeffs == 0 || progEvaluate == 0) {
            deleteProgramIfPresent(progDepthRange, progCoeffs, progEvaluate);
            translucentWaveletFailed = true;
            return false;
        }

        translucentProgramDepthRange = progDepthRange;
        translucentProgramCoeffs = progCoeffs;
        translucentProgramEvaluate = progEvaluate;
        var cached = translucentVariants.get(translucentKey);
        cached[0] = progDepthRange;
        cached[1] = progCoeffs;
        cached[2] = progEvaluate;
        FlwBackend.LOGGER.info("[gl_mesh_shader] translucent programs linked (depth_range={} coefficients={} "
                        + "evaluate={} command_builder={} gather={})", progDepthRange, progCoeffs, progEvaluate,
                translucentBuilderProgram, translucentGatherProgram);
        return true;
    }

    public int translucentMlabProgram(OitInsertMode mode) {
        return translucentMlabPrograms[mode.ordinal()];
    }

    public boolean ensureTranslucentMlabCompiled(OitInsertMode mode, boolean linear) {
        var sources = TerrainOverlayDraw.sources();
        if (translucentMlabKey == null || translucentMlabKey.linear() != linear || !translucentMlabKey.sources()
                                                                                                      .equals(sources)) {
            translucentMlabKey = new TranslucentKey(linear, sources);
            Arrays.fill(translucentMlabPrograms, 0);
            translucentMlabFailed = false;
            var cached = translucentMlabVariants.computeIfAbsent(translucentMlabKey,
                    _ -> new int[OitInsertMode.values().length]);
            System.arraycopy(cached, 0, translucentMlabPrograms, 0, cached.length);
        }
        if (translucentMlabFailed) {
            return false;
        }
        int idx = mode.ordinal();
        if (translucentMlabPrograms[idx] != 0) {
            return true;
        }
        int vert = compileShader(GL20C.GL_VERTEX_SHADER, "terrain/gl/translucent_pull.vert");
        int frag = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/translucent_frag.frag",
                translucentMlabFragDefines(mode, linear));
        if (vert == 0 || frag == 0) {
            deleteIfPresent(vert, frag);
            translucentMlabFailed = true;
            return false;
        }
        int prog = MeshGlPrograms.linkProgram("gl_mesh_shader",
                "translucent_mlab_" + mode.name().toLowerCase(Locale.ROOT), vert, frag);
        deleteIfPresent(vert, frag);
        if (prog == 0) {
            translucentMlabFailed = true;
            return false;
        }
        translucentMlabPrograms[idx] = prog;
        translucentMlabVariants.get(translucentMlabKey)[idx] = prog;
        FlwBackend.LOGGER.info("[gl_mesh_shader] translucent mlab program linked (mode={} handle={})", mode, prog);
        return true;
    }

    public void destroy() {
        for (var programs : opaqueVariants.values()) deleteProgramIfPresent(programs);
        opaqueVariants.clear();
        terrainProgramSolid = terrainProgramCutout = 0;
        lastOverlaySources = List.of();
        if (commandBuilderProgram != 0) {
            GL20C.glDeleteProgram(commandBuilderProgram);
            commandBuilderProgram = 0;
        }
        for (var variant : translucentVariants.values()) deleteProgramIfPresent(variant);
        translucentVariants.clear();
        translucentKey = null;
        translucentProgramDepthRange = 0;
        translucentProgramCoeffs = 0;
        translucentProgramEvaluate = 0;
        translucentBuilderProgram = 0;
        translucentGatherProgram = 0;
        for (var variant : translucentMlabVariants.values()) deleteProgramIfPresent(variant);
        translucentMlabVariants.clear();
        translucentMlabKey = null;
        Arrays.fill(translucentMlabPrograms, 0);
        translucentMlabFailed = false;
        builderFailed = false;
        lastOpaqueConfigKey = -1;
        translucentFailed = false;
        translucentWaveletFailed = false;
    }

    private record OverlayKey(int config, List<Identifier> sources) {
    }

    private record TranslucentKey(boolean linear, List<Identifier> sources) {
    }
}
