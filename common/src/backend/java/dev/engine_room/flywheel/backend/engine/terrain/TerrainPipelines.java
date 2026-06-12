package dev.engine_room.flywheel.backend.engine.terrain;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.function.Consumer;

public final class TerrainPipelines {
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
        case VERTEX -> assembleVertex();
        case FRAGMENT -> assembleFragment(id.equals(CUTOUT_FRAGMENT) || id.equals(CUTOUT_FRAGMENT_LINEAR),
                id.equals(SOLID_FRAGMENT_LINEAR) || id.equals(CUTOUT_FRAGMENT_LINEAR));
    };

    private static final RenderPipeline[][] pipelines = new RenderPipeline[2][2];

    private TerrainPipelines() {
    }

    public static RenderPipeline solid() {
        return getOrBuild(false);
    }

    public static RenderPipeline cutout() {
        return getOrBuild(true);
    }

    private static RenderPipeline getOrBuild(boolean cutout) {
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

    private static RenderPipeline build(boolean cutout, boolean linear) {
        BindGroupLayout samplers = BindGroupLayout.builder()
                                                  .withSampler("Sampler0")
                                                  .withSampler("Sampler2")
                                                  .build();

        Identifier fragment = linear
                ? (cutout ? CUTOUT_FRAGMENT_LINEAR : SOLID_FRAGMENT_LINEAR)
                : (cutout ? CUTOUT_FRAGMENT : SOLID_FRAGMENT);
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             .withLocation(ResourceUtil.rl(
                                     "pipeline/terrain/" + (cutout ? "cutout" : "solid") + (linear ? "_linear" : "")))
                             .withVertexShader(VERTEX)
                             .withFragmentShader(fragment)
                             .withVertexBinding(0, CompactChunkVertex.VERTEX_FORMAT)
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
        return ShaderAssembly.assembleFlattened(extra, List.of(FlwPrograms.SOURCES.get(VERTEX_SOURCE)));
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
}
