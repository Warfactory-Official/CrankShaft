package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.engine_room.flywheel.backend.compile.OitInsertMode;
import dev.engine_room.flywheel.backend.compile.OitMode;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
import dev.engine_room.flywheel.compat.backend.PipelineShaderSource;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Improved Transparency reroute producers: resolved layers and captured weather.
 */
public final class FabulousOitPipelines {
    private static final Identifier WEATHER_VERTEX = ResourceUtil.rl("codegen/oit/weather");
    private static final Identifier[] LAYER_FRAGMENT = new Identifier[OitMode.values().length];
    private static final Identifier[] WEATHER_FRAGMENT = new Identifier[OitMode.values().length];
    private static final Map<Identifier, OitMode> LAYER_FRAGMENT_MODE = new HashMap<>();
    private static final Map<Identifier, OitMode> WEATHER_FRAGMENT_MODE = new HashMap<>();
    private static final Map<Identifier, OitInsertMode> WEATHER_MLAB_MODE = new HashMap<>();
    private static final Map<OitMode, RenderPipeline> LAYER_CACHE = new EnumMap<>(OitMode.class);
    private static final Map<OitMode, RenderPipeline> WEATHER_CACHE = new EnumMap<>(OitMode.class);
    private static final Map<OitInsertMode, RenderPipeline> WEATHER_MLAB_CACHE = new EnumMap<>(OitInsertMode.class);
    private static final PipelineShaderSource SHADER_SOURCE = (id, type) -> switch (type) {
        case VERTEX -> id.equals(WEATHER_VERTEX)
                ? RenderPassShaders.assembleWeatherOitVertex()
                : RenderPassShaders.fullscreenVertex();
        case FRAGMENT -> {
            OitMode layerMode = LAYER_FRAGMENT_MODE.get(id);
            if (layerMode != null) {
                yield RenderPassShaders.assembleLayerOitFragment(layerMode);
            }
            OitMode weatherMode = WEATHER_FRAGMENT_MODE.get(id);
            if (weatherMode != null) {
                yield RenderPassShaders.assembleWeatherOitFragment(weatherMode);
            }
            yield RenderPassShaders.assembleWeatherMlabFragment(WEATHER_MLAB_MODE.get(id));
        }
    };

    static {
        for (OitMode mode : new OitMode[]{OitMode.DEPTH_RANGE, OitMode.GENERATE_COEFFICIENTS, OitMode.EVALUATE}) {
            Identifier layerId = ResourceUtil.rl("codegen/oit/layer_frag" + mode.name);
            LAYER_FRAGMENT[mode.ordinal()] = layerId;
            LAYER_FRAGMENT_MODE.put(layerId, mode);
            Identifier weatherId = ResourceUtil.rl("codegen/oit/weather_frag" + mode.name);
            WEATHER_FRAGMENT[mode.ordinal()] = weatherId;
            WEATHER_FRAGMENT_MODE.put(weatherId, mode);
        }
    }

    private FabulousOitPipelines() {
    }

    public static RenderPipeline layerProducer(OitMode mode) {
        RenderPipeline pipeline = LAYER_CACHE.computeIfAbsent(mode, FabulousOitPipelines::buildLayerProducer);
        RenderPipelineCompiler.precompile(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildLayerProducer(OitMode mode) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                       .withLocation(ResourceUtil.rl("pipeline/oit/layer" + mode.name))
                                                       .withVertexShader(OitPipelines.FULLSCREEN_VERTEX)
                                                       .withFragmentShader(LAYER_FRAGMENT[mode.ordinal()])
                                                       .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                       .withDepthStencilState(OitPipelines.OIT_PRODUCER_DEPTH_STATE)
                                                       .withCull(false)
                                                       .withBindGroupLayout(OitPipelines.withOitReadSamplers(
                                                               BindGroupLayout.builder()
                                                                              .withImageSampler("_flw_layerColor")
                                                                              .withImageSampler("_flw_layerDepth"),
                                                               mode).build());
        return OitPipelines.withOitColorTargets(builder, mode, "Layer-OIT").build();
    }

    public static RenderPipeline weatherProducer(OitMode mode) {
        RenderPipeline pipeline = WEATHER_CACHE.computeIfAbsent(mode, FabulousOitPipelines::buildWeatherProducer);
        RenderPipelineCompiler.precompile(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildWeatherProducer(OitMode mode) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                       .withLocation(
                                                               ResourceUtil.rl("pipeline/oit/weather" + mode.name))
                                                       .withVertexShader(WEATHER_VERTEX)
                                                       .withFragmentShader(WEATHER_FRAGMENT[mode.ordinal()])
                                                       .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
                                                       .withPrimitiveTopology(PrimitiveTopology.QUADS)
                                                       .withDepthStencilState(OitPipelines.OIT_PRODUCER_DEPTH_STATE)
                                                       .withCull(false)
                                                       .withBindGroupLayout(OitPipelines.withOitReadSamplers(
                                                               BindGroupLayout.builder()
                                                                              .withImageSampler("Sampler0")
                                                                              .withImageSampler("Sampler2"),
                                                               mode).build());
        return OitPipelines.withOitColorTargets(builder, mode, "Weather-OIT").build();
    }

    public static RenderPipeline weatherMlab(OitInsertMode mode) {
        RenderPipeline pipeline = WEATHER_MLAB_CACHE.computeIfAbsent(mode, FabulousOitPipelines::buildWeatherMlab);
        RenderPipelineCompiler.precompile(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildWeatherMlab(OitInsertMode mode) {
        String name = mode.name().toLowerCase(Locale.ROOT);
        Identifier fragment = ResourceUtil.rl("codegen/oit/mlab/weather_frag_" + name);
        WEATHER_MLAB_MODE.put(fragment, mode);
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             .withLocation(ResourceUtil.rl("pipeline/oit/mlab/weather_" + name))
                             .withVertexShader(WEATHER_VERTEX)
                             .withFragmentShader(fragment)
                             .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
                             .withPrimitiveTopology(PrimitiveTopology.QUADS)
                             .withDepthStencilState(OitPipelines.OIT_PRODUCER_DEPTH_STATE)
                             .withCull(false)
                             .withBindGroupLayout(BindGroupLayout.builder()
                                                                 .withImageSampler("Sampler0")
                                                                 .withImageSampler("Sampler2")
                                                                 .build())
                             .withColorTargetState(0, OitPipelines.MLAB_NO_COLOR)
                             .build();
    }
}
