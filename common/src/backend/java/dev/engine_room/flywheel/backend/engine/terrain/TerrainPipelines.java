package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class TerrainPipelines {
    // Mirrors the guest instance OIT producers: depth range MAXes, the other two accumulate.
    private static final BlendFunction MAX_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX);
    private static final BlendFunction ADD_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.ADD);
    private static final Identifier VERTEX = ResourceUtil.rl("codegen/terrain/vertex");
    private static final Identifier SOLID_FRAGMENT = ResourceUtil.rl("codegen/terrain/solid_frag");
    private static final Identifier CUTOUT_FRAGMENT = ResourceUtil.rl("codegen/terrain/cutout_frag");
    private static final Identifier SOLID_FRAGMENT_LINEAR = ResourceUtil.rl("codegen/terrain/solid_frag_linear");
    private static final Identifier CUTOUT_FRAGMENT_LINEAR = ResourceUtil.rl("codegen/terrain/cutout_frag_linear");

    private static final Identifier VERTEX_SOURCE = ResourceUtil.rl("terrain/terrain_solid.vert");
    private static final Identifier SOLID_FRAGMENT_SOURCE = ResourceUtil.rl("terrain/terrain_solid.frag");
    private static final Identifier CUTOUT_FRAGMENT_SOURCE = ResourceUtil.rl("terrain/terrain_cutout.frag");

    private static final Identifier TEXEL_FILTER = ResourceUtil.rl("internal/texel_filter.glsl");

    private static final ShaderSource SHADER_SOURCE = (id, type) -> switch (type) {
        case VERTEX -> assembleVertex(ctx -> ctx.requireExtension("GL_ARB_shader_draw_parameters"));
        case FRAGMENT -> assembleFragment(id.equals(CUTOUT_FRAGMENT) || id.equals(CUTOUT_FRAGMENT_LINEAR),
                id.equals(SOLID_FRAGMENT_LINEAR) || id.equals(CUTOUT_FRAGMENT_LINEAR));
    };

    private static final RenderPipeline[][] pipelines = new RenderPipeline[2][2];
    // Iris's shadow pass: own programs (ProgramId.Shadow*) and no back-face culling, so its own pipeline identity.
    private static final RenderPipeline[] shadowPipelines = new RenderPipeline[2];
    // Guest translucent producers: depth range, coefficients, evaluate, or single-pass deferred capture.
    private static final RenderPipeline[] oitPipelines = new RenderPipeline[4];
    // Dropped when Iris swaps the chunk vertex type: both the binding layout and the assembled source change.
    private static @Nullable VertexFormat builtFor;

    private TerrainPipelines() {
    }

    public static RenderPipeline solid() {
        return getOrBuild(false);
    }

    public static RenderPipeline cutout() {
        return getOrBuild(true);
    }

    /**
     * {@code pass}: 0 depth range, 1 coefficients, 2 evaluate, 3 deferred capture. The producer binds its framebuffer, so only
     * the blend and depth state ride the pipeline.
     */
    public static RenderPipeline translucentOit(int pass) {
        VertexFormat format = TerrainVertexFormat.current();
        if (builtFor != format) {
            getOrBuild(false);
        }
        RenderPipeline pipeline = oitPipelines[pass];
        if (pipeline == null) {
            pipeline = buildOit(pass);
            oitPipelines[pass] = pipeline;
        }
        RenderSystem.getDevice().precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    public static RenderPipeline shadow(boolean cutout) {
        VertexFormat format = TerrainVertexFormat.current();
        if (builtFor != format) {
            getOrBuild(false);
        }
        int c = cutout ? 1 : 0;
        RenderPipeline pipeline = shadowPipelines[c];
        if (pipeline == null) {
            pipeline = buildShadow(cutout);
            shadowPipelines[c] = pipeline;
        }
        RenderSystem.getDevice().precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline getOrBuild(boolean cutout) {
        VertexFormat format = TerrainVertexFormat.current();
        if (builtFor != format) {
            builtFor = format;
            for (RenderPipeline[] row : pipelines) {
                Arrays.fill(row, null);
            }
            Arrays.fill(shadowPipelines, null);
            Arrays.fill(oitPipelines, null);
        }
        boolean linear = TerrainAtlasFilter.linear();
        int c = cutout ? 1 : 0;
        int l = linear ? 1 : 0;
        RenderPipeline pipeline = pipelines[c][l];
        if (pipeline == null) {
            pipeline = build(cutout, linear);
            pipelines[c][l] = pipeline;
        }
        RenderSystem.getDevice().precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    /**
     * Which terrain pipeline this is, or {@code null} if it is not one. Reads the cache without building, so a
     * shaderpack guest can claim these pipelines from inside pipeline compilation.
     */
    public static @Nullable Kind terrainKind(RenderPipeline pipeline) {
        for (int pass = 0; pass < oitPipelines.length; pass++) {
            if (oitPipelines[pass] == pipeline) {
                return new Kind(false, false, pass);
            }
        }
        for (int cutout = 0; cutout < pipelines.length; cutout++) {
            for (RenderPipeline cached : pipelines[cutout]) {
                if (cached == pipeline) {
                    return new Kind(cutout == 1, false);
                }
            }
        }
        for (int cutout = 0; cutout < shadowPipelines.length; cutout++) {
            if (shadowPipelines[cutout] == pipeline) {
                return new Kind(cutout == 1, true);
            }
        }
        return null;
    }

    private static RenderPipeline buildOit(int pass) {
        boolean capture = pass == 3;
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             .withLocation(ResourceUtil.rl("pipeline/terrain/oit_" + pass
                                     + (TerrainVertexFormat.extended() ? "_ext" : "")))
                             .withVertexShader(VERTEX)
                             .withFragmentShader(SOLID_FRAGMENT)
                             .withVertexBinding(0, TerrainVertexFormat.current())
                             .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                             .withDepthStencilState(
                                     new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, capture, 0.0f, 0.0f))
                             .withColorTargetState(new ColorTargetState(
                                     capture ? Optional.empty() : Optional.of(pass == 0 ? MAX_BLEND : ADD_BLEND),
                                     GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                             .withCull(true)
                             .withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION)
                             .withBindGroupLayout(samplerLayout().build())
                             .build();
    }

    private static RenderPipeline buildShadow(boolean cutout) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             .withLocation(ResourceUtil.rl("pipeline/terrain/shadow_" + (cutout ? "cutout" : "solid")
                                     + (TerrainVertexFormat.extended() ? "_ext" : "")))
                             .withVertexShader(VERTEX)
                             .withFragmentShader(cutout ? CUTOUT_FRAGMENT : SOLID_FRAGMENT)
                             .withVertexBinding(0, TerrainVertexFormat.current())
                             .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                             // Iris emits forward-Z shadow positions. Its depth-state inversion only recognizes
                             // its own pipeline identities, so this guest must select the forward comparison.
                             .withDepthStencilState(
                                     new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0.0f, 0.0f))
                             .withCull(false)
                             .withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION)
                             .withBindGroupLayout(samplerLayout().build())
                             .build();
    }

    private static BindGroupLayout.Builder samplerLayout() {
        BindGroupLayout.Builder samplerLayout = BindGroupLayout.builder()
                                                               .withSampler("Sampler0")
                                                               .withSampler("Sampler2");
        if (GuestTerrainGate.ENABLED) {
            // A shaderpack's Sodium-patched terrain program reads these two exactly as Sodium's own program does,
            // so they are declared on the pipeline and fed with setUniform rather than bound by hand.
            samplerLayout.withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
                         .withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT);
        }
        return samplerLayout;
    }

    private static RenderPipeline build(boolean cutout, boolean linear) {
        BindGroupLayout samplers = samplerLayout().build();

        Identifier fragment = linear
                ? (cutout ? CUTOUT_FRAGMENT_LINEAR : SOLID_FRAGMENT_LINEAR)
                : (cutout ? CUTOUT_FRAGMENT : SOLID_FRAGMENT);
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             // The layout rides the location: a compact and an extended pipeline must not share a
                             // compiled-program cache entry.
                             .withLocation(ResourceUtil.rl(
                                     "pipeline/terrain/" + (cutout ? "cutout" : "solid") + (linear ? "_linear" : "")
                                             + (TerrainVertexFormat.extended() ? "_ext" : "")))
                             .withVertexShader(VERTEX)
                             .withFragmentShader(fragment)
                             .withVertexBinding(0, TerrainVertexFormat.current())
                             .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                             // T2's HiZ pyramid reads this depth.
                             .withDepthStencilState(
                                     new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, 0.0f, 0.0f))
                             .withCull(true)
                             .withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION)
                             .withBindGroupLayout(samplers)
                             .build();
    }

    public static String assembleVertex() {
        return assembleVertex(ShaderAssembly.NO_EXTRA);
    }

    public static String assembleVertex(Consumer<Compilation> extra) {
        return ShaderAssembly.assembleFlattened(extra.andThen(TerrainVertexFormat::appendDefines),
                List.of(FlwPrograms.SOURCES.get(VERTEX_SOURCE)));
    }

    public static String assembleFragment(boolean cutout, boolean linear) {
        return assembleFragment(cutout, linear, ShaderAssembly.NO_EXTRA);
    }

    public static String assembleFragment(boolean cutout, boolean linear, Consumer<Compilation> extra) {
        Identifier source = cutout ? CUTOUT_FRAGMENT_SOURCE : SOLID_FRAGMENT_SOURCE;
        // globals.glsl declares the Globals UBO (UseRgss) that texel_filter reads -- the import lands ahead of the
        // roots. The FLW_PIXEL_FILTER_LINEAR define (when set) gates flw_sampleAtlas to the smooth branch.
        return ShaderAssembly.assembleFlattened(ctx -> {
            ctx.mojImport("minecraft:globals.glsl");
            if (linear) {
                ctx.define(TerrainAtlasFilter.LINEAR_DEFINE);
            }
            extra.accept(ctx);
        }, List.of(FlwPrograms.SOURCES.get(TEXEL_FILTER), FlwPrograms.SOURCES.get(source)));
    }

    /**
     * {@code oitPass} >= 0 = a translucent OIT producer; otherwise an opaque solid/cutout pipeline.
     */
    public record Kind(boolean cutout, boolean shadow, int oitPass) {
        public Kind(boolean cutout, boolean shadow) {
            this(cutout, shadow, -1);
        }
    }
}
