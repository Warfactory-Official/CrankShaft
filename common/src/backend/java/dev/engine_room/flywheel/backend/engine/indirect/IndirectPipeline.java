package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.compile.LightSmoothness;
import dev.engine_room.flywheel.backend.compile.ProgramGeneration;
import dev.engine_room.flywheel.backend.compile.RenderPassShaders;
import dev.engine_room.flywheel.backend.compile.RenderPipelineCompiler;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly;
import dev.engine_room.flywheel.backend.compile.StatePipelines;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.DrawState;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.compat.backend.PipelineShaderSource;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class IndirectPipeline {
    private static final Map<Identifier, VertexAssembly> VERTEX_ASSEMBLY = new HashMap<>();
    private static final Map<Identifier, FragmentAssembly> FRAGMENT_ASSEMBLY = new HashMap<>();
    private static final PipelineShaderSource SHADER_SOURCE = (id, type) -> switch (type) {
        case VERTEX -> {
            VertexAssembly va = VERTEX_ASSEMBLY.get(id);
            yield RenderPassShaders.assembleUberIndirectVertex(va.material(), va.debug(),
                    RenderPassShaders.maybeBindlessGl().andThen(embeddedExtra(va.embedded()))
                                     .andThen(farExtra(va.bake(), va.surface())), va.far());
        }
        case FRAGMENT -> {
            FragmentAssembly fa = FRAGMENT_ASSEMBLY.get(id);
            yield RenderPassShaders.uberFragment(fa.light(), fa.material(), fa.smoothness(), fa.debug(),
                    RenderPassShaders.maybeBindlessGl().andThen(embeddedExtra(fa.embedded()))
                                     .andThen(farExtra(fa.bake(), fa.surface())));
        }
    };
    private static final StatePipelines<UberKey, DrawState> UBER = new StatePipelines<>(
            (key, state, generation) -> new RenderPipeline[]{buildUber(key, state, generation)},
            (_, pipeline) -> request(pipeline));
    private static final StatePipelines<UberKey, DrawState> FAR_UBER = new StatePipelines<>(
            (key, state, generation) -> new RenderPipeline[]{buildUber(key, state, generation),
                    buildUber(key.bakeKey(), state, generation)}, (_, pipeline) -> request(pipeline));

    private IndirectPipeline() {
    }

    /**
     * The uber pipeline of the material's shaders and state from the newest published generation, null before the
     * first; requests the current generation's. {@code embedded}: the {@link RenderPassShaders#readsEmbedded} variant
     * of an embedded run; {@code prepare}: as {@link StatePipelines#select}.
     */
    public static @Nullable RenderPipeline uberPipelineFor(Material material, boolean embedded, boolean prepare) {
        var key = new UberKey(material.light(), material.shaders(), BackendConfig.INSTANCE.lightSmoothness(),
                FrameUniforms.debugMode(), embedded, false, false, false);
        var group = UBER.select(key, DrawState.of(material), ProgramGeneration.current(), prepare);
        return group == null ? null : group[0];
    }

    /**
     * Far draw and rest-pose bake programs publish together; null until their first shared generation is ready.
     * Outside any render pass.
     */
    public static @Nullable RenderPipeline @Nullable [] farPipelinesFor(Material material, boolean surface) {
        var key = new UberKey(material.light(), material.shaders(), BackendConfig.INSTANCE.lightSmoothness(),
                FrameUniforms.debugMode(), false, true, false, surface);
        return FAR_UBER.select(key, DrawState.of(material), ProgramGeneration.current(), true);
    }

    private static void request(RenderPipeline pipeline) {
        RenderPipelineCompiler.request(pipeline, SHADER_SOURCE, null);
    }

    private static Consumer<Compilation> embeddedExtra(boolean embedded) {
        return embedded ? RenderPassShaders.EMBEDDED : ShaderAssembly.NO_EXTRA;
    }

    private static Consumer<Compilation> farExtra(boolean bake, boolean surface) {
        return bake ? RenderPassShaders.FAR_BAKE
                : surface ? RenderPassShaders.FAR_SURFACE : ShaderAssembly.NO_EXTRA;
    }

    private static Identifier uberVertexId(MaterialShaders materialShaders, boolean debug, boolean embedded,
                                           boolean far, boolean bake, ProgramGeneration generation) {
        return ResourceUtil.rl("codegen/indirect/uber/" + (embedded ? "embedded/" : "")
                + (bake ? "far_bake/" : far ? "far/" : "") + "g" + generation.types()
                + "__" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource())
                + (debug ? "__debug" : ""));
    }

    private static Identifier uberFragmentId(LightShader light, MaterialShaders materialShaders,
                                             LightSmoothness smoothness, DebugMode debug, boolean embedded,
                                             boolean bake, boolean surface, ProgramGeneration generation) {
        return ResourceUtil.rl("codegen/indirect_frag/uber/" + (embedded ? "embedded/" : "")
                + (bake ? "far_bake/" : surface ? "far_surface/" : "") + "g" + generation.cutouts()
                + "_" + generation.fogs()
                + "__" + ResourceUtil.toDebugFileNameNoExtension(light.source())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                + "__" + smoothness.getSerializedName()
                + (debug == DebugMode.OFF ? "" : "__debug_" + debug.getSerializedName()));
    }

    private static RenderPipeline buildUber(UberKey key, DrawState state, ProgramGeneration generation) {
        boolean debug = key.debug() != DebugMode.OFF;
        Identifier vertex = uberVertexId(key.materialShaders(), debug, key.embedded(), key.far(), key.bake(),
                generation);
        Identifier fragment = uberFragmentId(key.light(), key.materialShaders(), key.smoothness(), key.debug(),
                key.embedded(), key.bake(), key.surface(), generation);
        VERTEX_ASSEMBLY.putIfAbsent(vertex,
                new VertexAssembly(key.materialShaders(), debug, key.embedded(), key.far(), key.bake(), false));
        FRAGMENT_ASSEMBLY.putIfAbsent(fragment, new FragmentAssembly(key.light(), key.materialShaders(),
                key.smoothness(), key.debug(), key.embedded(), key.bake(), key.surface()));
        return stateBuilder(state.transparency(), state.depthTest(), state.depthWrite(), state.colorWrite(),
                state.cull(), state.polygonOffset(), GlCompat.SUPPORTS_BINDLESS_TEXTURES)
                .withLocation(ResourceUtil.rl("pipeline/indirect/uber_" + key.cacheName() + state.cacheName()))
                .withVertexShader(vertex)
                .withFragmentShader(fragment)
                .build();
    }

    /**
     * Everything but location and shaders: the bind group {@link IndirectDrawManager} binds by name, plus the
     * material's depth/cull/blend state. {@code bindless}: no {@code Sampler0}.
     */
    public static RenderPipeline.Builder stateBuilder(Transparency transparency, DepthTest depthTest,
                                                      boolean depthWrite, boolean colorWrite, boolean cull,
                                                      boolean polygonOffset, boolean bindless) {
        BindGroupLayout.Builder bindGroup = BindGroupLayout.builder();
        if (!bindless) {
            bindGroup.withImageSampler("Sampler0");
        }
        bindGroup.withImageSampler("Sampler1")
                 .withImageSampler("Sampler2")
                 .withUniform("_FlwRenderOrigin", UniformType.UNIFORM_BUFFER);

        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                       .withVertexBinding(0, InternalVertex.VERTEX_FORMAT)
                                                       .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                       .withDepthStencilState(
                                                               new DepthStencilState(depthTest.compareOp, depthWrite,
                                                                       polygonOffset ? MaterialRenderState.polygonOffsetSlope(
                                                                               transparency, depthTest) : 0.0f,
                                                                       polygonOffset ? MaterialRenderState.POLYGON_OFFSET_UNITS : 0.0f))
                                                       .withCull(cull)
                                                       .withBindGroupLayout(bindGroup.build());

        ColorTargetState colorTarget = uberColorTarget(transparency, colorWrite);
        if (colorTarget != null) {
            builder.withColorTargetState(colorTarget);
        }
        return builder;
    }

    private static ColorTargetState uberColorTarget(Transparency transparency, boolean colorWrite) {
        BlendFunction blend = switch (transparency) {
            case OPAQUE -> null;
            case ADDITIVE -> BlendFunction.ADDITIVE;
            case LIGHTNING, ORDER_INDEPENDENT_ADDITIVE -> BlendFunction.LIGHTNING;
            case GLINT -> BlendFunction.GLINT;
            case CRUMBLING, TRANSLUCENT, ORDER_INDEPENDENT -> BlendFunction.TRANSLUCENT;
            case TRANSLUCENT_ALPHA_REPLACE -> new BlendFunction(BlendFactor.SRC_ALPHA,
                    BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ZERO);
        };

        if (blend == null && colorWrite) {
            #if MC >= 26.3
            return ColorTargetState.DEFAULT;
            #else
            return null;
            #endif
        }

        int writeMask = colorWrite ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_NONE;
        return new ColorTargetState(Optional.ofNullable(blend), GpuFormat.RGBA8_UNORM, writeMask);
    }

    private record VertexAssembly(MaterialShaders material, boolean debug, boolean embedded, boolean far,
                                  boolean bake, boolean surface) {
    }

    private record FragmentAssembly(LightShader light, MaterialShaders material, LightSmoothness smoothness,
                                    DebugMode debug, boolean embedded, boolean bake, boolean surface) {
    }

    private record UberKey(LightShader light, MaterialShaders materialShaders, LightSmoothness smoothness,
                           DebugMode debug, boolean embedded, boolean far, boolean bake, boolean surface) {
        UberKey bakeKey() {
            return new UberKey(light, materialShaders, smoothness, debug, false, true, true, false);
        }

        String cacheName() {
            return ResourceUtil.toDebugFileNameNoExtension(light.source())
                    + "_" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource())
                    + "_" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                    + "_" + smoothness.getSerializedName()
                    + (debug == DebugMode.OFF ? "" : "_debug_" + debug.getSerializedName())
                    + (embedded ? "_embedded" : "") + (bake ? "_far_bake" : far ? "_far" : "")
                    + (surface ? "_surface" : "");
        }
    }
}
