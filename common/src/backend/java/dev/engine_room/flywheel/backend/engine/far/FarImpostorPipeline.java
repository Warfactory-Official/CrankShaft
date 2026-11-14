package dev.engine_room.flywheel.backend.engine.far;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
import dev.engine_room.flywheel.compat.backend.PipelineShaderSource;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The far impostor boxes: no vertex input, 36 vertices per baked part, depth written per ray-marched texel.
 */
final class FarImpostorPipeline {
    private static final Identifier VERTEX = ResourceUtil.rl("codegen/far/impostor");
    private static final Identifier FRAGMENT = ResourceUtil.rl("codegen/far/impostor_frag");
    private static final PipelineShaderSource SHADER_SOURCE = (id, type) -> switch (type) {
        case VERTEX -> RenderPassShaders.assembleFarImpostorVertex(id.getPath().endsWith("_surface"));
        case FRAGMENT -> RenderPassShaders.assembleFarImpostorFragment(id.getPath().endsWith("_surface"));
    };

    private static @Nullable RenderPipeline pipeline;
    private static @Nullable RenderPipeline surfacePipeline;

    private FarImpostorPipeline() {
    }

    static @Nullable RenderPipeline get(boolean surface) {
        RenderPipeline selected = surface ? surfacePipeline : pipeline;
        if (selected == null) {
            String suffix = surface ? "_surface" : "";
            RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                           .withLocation(
                                                                   ResourceUtil.rl("pipeline/far/impostor" + suffix))
                                                           .withVertexShader(ResourceUtil.rl(VERTEX.getPath() + suffix))
                                                           .withFragmentShader(
                                                                   ResourceUtil.rl(FRAGMENT.getPath() + suffix))
                                                           .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                           .withDepthStencilState(new DepthStencilState(
                                                                   CompareOp.GREATER_THAN_OR_EQUAL, true, 0.0f, 0.0f))
                                                           .withCull(true)
                                                           .withBindGroupLayout(BindGroupLayout.builder()
                                                                                               .withImageSampler(
                                                                                                       "Sampler2")
                                                                                               .withUniform(
                                                                                                       "_FlwRenderOrigin",
                                                                                                       UniformType.UNIFORM_BUFFER)
                                                                                               .build());
            #if MC >= 26.3
            builder.withColorTargetState(ColorTargetState.DEFAULT);
            #endif
            selected = builder.build();
            if (surface) {
                surfacePipeline = selected;
            } else {
                pipeline = selected;
            }
        }
        RenderPipelineCompiler.request(selected, SHADER_SOURCE, null);
        return RenderPipelineCompiler.ready(selected) ? selected : null;
    }
}
