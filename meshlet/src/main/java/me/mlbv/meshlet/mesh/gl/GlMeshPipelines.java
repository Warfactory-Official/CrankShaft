// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock
package me.mlbv.meshlet.mesh.gl;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.gl.shader.MeshGlPrograms;
import me.mlbv.meshlet.mesh.shared.MeshShaderPrep;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.NVMeshShader;

// Each program is assembled through flywheel's shared Compilation pipeline, then compiled/linked with the raw-GL
// pattern that mirrors the working MeshHelloTriangle (driver log surfaced verbatim on failure), multi-stage NV.
public final class GlMeshPipelines {
    private int terrainProgramSolid = 0;
    private int terrainProgramCutout = 0;
    private int commandBuilderProgram = 0;
    private boolean builderFailed = false;
    private int lastOpaqueConfigKey = -1;

    private int gatherProgram = 0;
    private boolean gatherFailed = false;

    private int translucentBuilderProgram = 0;
    private int translucentGatherProgram = 0;
    private int translucentProgramDepthRange = 0;
    private int translucentProgramCoeffs = 0;
    private int translucentProgramEvaluate = 0;
    private boolean translucentFailed = false;
    private int lastTranslucentLinear = -1;

    private final int[] translucentMlabPrograms = new int[OitInsertMode.values().length];
    private boolean translucentMlabFailed = false;
    private int lastTranslucentMlabLinear = -1;

    public GlMeshPipelines() {
    }

    public void warmUp() {
        ensureCompiled(MeshFeatureConfig.currentKey());
        gatherProgram();
        boolean linear = TerrainAtlasFilter.linear();
        ensureTranslucentCompiled(linear);
        for (OitInsertMode mode : OitInsertMode.values()) {
            if (mode == OitInsertMode.MLAB && !GlCompat.SUPPORTS_FRAGMENT_INTERLOCK) {
                continue;
            }
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
        if (configKey != lastOpaqueConfigKey) {
            // First call or a video-settings change: rebuild with this key's #define block. A failing config
            // compiles ONCE (key recorded); flipping keys retries, so a buggy variant never strands a good one.
            lastOpaqueConfigKey = configKey;
            recompileTerrainProgram(configKey);
        }
        return terrainProgramSolid != 0 && terrainProgramCutout != 0 && commandBuilderProgram != 0;
    }

    private void recompileTerrainProgram(int configKey) {
        if (terrainProgramSolid != 0) {
            GL20C.glDeleteProgram(terrainProgramSolid);
            terrainProgramSolid = 0;
        }
        if (terrainProgramCutout != 0) {
            GL20C.glDeleteProgram(terrainProgramCutout);
            terrainProgramCutout = 0;
        }
        Consumer<Compilation> features = ctx -> MeshFeatureConfig.applyFeatureDefines(ctx, configKey);
        Consumer<Compilation> fragExtras = features.andThen(ctx -> MeshFeatureConfig.applyFragExtensions(ctx, configKey));
        int task = compileShader(NVMeshShader.GL_TASK_SHADER_NV, "terrain/gl/task.task", features);
        int mesh = compileShader(NVMeshShader.GL_MESH_SHADER_NV, "terrain/gl/mesh.mesh", features);
        int fragSolid = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/frag.frag",
                fragExtras.andThen(ctx -> ctx.define("MESHLET_SOLID_PASS")));
        int fragCutout = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/frag.frag", fragExtras);
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
        FlwBackend.LOGGER.info("[gl_mesh_shader] terrain programs linked (solid={} cutout={} configKey={})",
                solid, cutout, configKey);
    }

    public int gatherProgram() {
        if (gatherProgram == 0 && !gatherFailed) {
            int comp = compileShader(GL43C.GL_COMPUTE_SHADER, "terrain/gl/gather.comp");
            int prog = comp != 0 ? MeshGlPrograms.linkProgram("gl_mesh_shader", "gather", comp) : 0;
            deleteIfPresent(comp);
            if (prog == 0) {
                gatherFailed = true;
            } else {
                gatherProgram = prog;
            }
        }
        return gatherProgram;
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

    public boolean ensureTranslucentCompiled(boolean linear) {
        int want = linear ? 1 : 0;
        if (want != lastTranslucentLinear) {
            deleteProgramIfPresent(translucentProgramDepthRange, translucentProgramCoeffs,
                    translucentProgramEvaluate, translucentBuilderProgram, translucentGatherProgram);
            translucentProgramDepthRange = 0;
            translucentProgramCoeffs = 0;
            translucentProgramEvaluate = 0;
            translucentBuilderProgram = 0;
            translucentGatherProgram = 0;
            translucentFailed = false;
            lastTranslucentLinear = want;
        }
        if (translucentFailed) {
            return false;
        }
        if (translucentBuilderProgram != 0 && translucentGatherProgram != 0 && translucentProgramDepthRange != 0
                && translucentProgramCoeffs != 0 && translucentProgramEvaluate != 0) {
            return true;
        }

        int vert = compileShader(GL20C.GL_VERTEX_SHADER, "terrain/gl/translucent_pull.vert");
        int gather = compileShader(GL43C.GL_COMPUTE_SHADER, "terrain/gl/translucent_gather.comp");
        int comp = compileShader(GL43C.GL_COMPUTE_SHADER, "terrain/gl/translucent_command_buffer_builder.comp");
        int fragDepthRange = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/translucent_frag.frag",
                translucentFragDefines(OitMode.DEPTH_RANGE, linear));
        int fragCoeffs = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/translucent_frag.frag",
                translucentFragDefines(OitMode.GENERATE_COEFFICIENTS, linear));
        int fragEvaluate = compileShader(GL20C.GL_FRAGMENT_SHADER, "terrain/gl/translucent_frag.frag",
                translucentFragDefines(OitMode.EVALUATE, linear));

        if (vert == 0 || gather == 0 || comp == 0 || fragDepthRange == 0 || fragCoeffs == 0 || fragEvaluate == 0) {
            deleteIfPresent(vert, gather, comp, fragDepthRange, fragCoeffs, fragEvaluate);
            translucentFailed = true;
            return false;
        }

        int progDepthRange = MeshGlPrograms.linkProgram("gl_mesh_shader", "translucent_depth_range", vert, fragDepthRange);
        int progCoeffs = MeshGlPrograms.linkProgram("gl_mesh_shader", "translucent_coefficients", vert, fragCoeffs);
        int progEvaluate = MeshGlPrograms.linkProgram("gl_mesh_shader", "translucent_evaluate", vert, fragEvaluate);
        int builder = MeshGlPrograms.linkProgram("gl_mesh_shader", "translucent_command_builder", comp);
        int gatherProg = MeshGlPrograms.linkProgram("gl_mesh_shader", "translucent_gather", gather);
        deleteIfPresent(vert, gather, comp, fragDepthRange, fragCoeffs, fragEvaluate);

        if (progDepthRange == 0 || progCoeffs == 0 || progEvaluate == 0 || builder == 0 || gatherProg == 0) {
            deleteProgramIfPresent(progDepthRange, progCoeffs, progEvaluate, builder, gatherProg);
            translucentFailed = true;
            return false;
        }

        translucentProgramDepthRange = progDepthRange;
        translucentProgramCoeffs = progCoeffs;
        translucentProgramEvaluate = progEvaluate;
        translucentBuilderProgram = builder;
        translucentGatherProgram = gatherProg;
        FlwBackend.LOGGER.info("[gl_mesh_shader] translucent programs linked (depth_range={} coefficients={} "
                        + "evaluate={} command_builder={} gather={})", progDepthRange, progCoeffs, progEvaluate,
                builder, gatherProg);
        return true;
    }

    public int translucentMlabProgram(OitInsertMode mode) {
        return translucentMlabPrograms[mode.ordinal()];
    }

    public boolean ensureTranslucentMlabCompiled(OitInsertMode mode, boolean linear) {
        int want = linear ? 1 : 0;
        if (want != lastTranslucentMlabLinear) {
            deleteProgramIfPresent(translucentMlabPrograms);
            Arrays.fill(translucentMlabPrograms, 0);
            translucentMlabFailed = false;
            lastTranslucentMlabLinear = want;
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
        FlwBackend.LOGGER.info("[gl_mesh_shader] translucent mlab program linked (mode={} handle={})", mode, prog);
        return true;
    }

    private static Consumer<Compilation> translucentMlabFragDefines(OitInsertMode mode, boolean linear) {
        return ctx -> {
            RenderPassShaders.mlabProducerDefines(ctx, mode);
            if (linear) {
                ctx.define(TerrainAtlasFilter.LINEAR_DEFINE);
            }
        };
    }

    public void destroy() {
        if (terrainProgramSolid != 0) {
            GL20C.glDeleteProgram(terrainProgramSolid);
            terrainProgramSolid = 0;
        }
        if (terrainProgramCutout != 0) {
            GL20C.glDeleteProgram(terrainProgramCutout);
            terrainProgramCutout = 0;
        }
        if (commandBuilderProgram != 0) {
            GL20C.glDeleteProgram(commandBuilderProgram);
            commandBuilderProgram = 0;
        }
        if (gatherProgram != 0) {
            GL20C.glDeleteProgram(gatherProgram);
            gatherProgram = 0;
        }
        gatherFailed = false;
        deleteProgramIfPresent(translucentProgramDepthRange, translucentProgramCoeffs,
                translucentProgramEvaluate, translucentBuilderProgram, translucentGatherProgram);
        translucentProgramDepthRange = 0;
        translucentProgramCoeffs = 0;
        translucentProgramEvaluate = 0;
        translucentBuilderProgram = 0;
        translucentGatherProgram = 0;
        deleteProgramIfPresent(translucentMlabPrograms);
        Arrays.fill(translucentMlabPrograms, 0);
        translucentMlabFailed = false;
        lastTranslucentMlabLinear = -1;
        builderFailed = false;
        lastOpaqueConfigKey = -1;
        translucentFailed = false;
        lastTranslucentLinear = -1;
    }

    private static int compileShader(int glType, String path) {
        return compileShader(glType, path, ctx -> {
        });
    }

    // Assemble via flywheel's Compilation pipeline: #version + config-global defines + this call's extras, then
    // meshlet's include-resolved body (its own #version stripped -- Compilation emits it).
    private static int compileShader(int glType, String path, Consumer<Compilation> extraDefines) {
        String src = ShaderAssembly.assemble(ctx -> {
            MeshShaderPrep.applyGlobalDefines(ctx);
            extraDefines.accept(ctx);
        }, List.of(FlwPrograms.SOURCES.get(meshletId(path))));
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
}
