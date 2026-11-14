package dev.engine_room.flywheel.backend.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.NativeOitProducers.Variant;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Adapts native Improved Transparency producers while their prepared draws run inside an engine OIT stage.
 * Scope, pipeline compilation and resource invalidation run on the render thread.
 */
public final class NativeOitPipelines {
    private static final BlendFunction MAX_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX,
            BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX);
    private static final ColorTargetState DEPTH = new ColorTargetState(Optional.of(MAX_BLEND),
            GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL);
    private static final ColorTargetState ADDITIVE = new ColorTargetState(Optional.of(BlendFunction.ADDITIVE),
            GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL);
    private static final ColorTargetState NO_COLOR = new ColorTargetState(Optional.empty(),
            GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE);
    private static final Map<RenderPipeline, EnumMap<Variant, AdaptedPipeline>> PIPELINES = new IdentityHashMap<>();
    private static final Map<List<Identifier>, Map<RenderPipeline, EnumMap<Variant, AdaptedPipeline>>> OVERLAY_PIPELINES =
            new HashMap<>();
    // Foreign owners register their current declarations again after resource invalidation.
    private static final Set<RenderPipeline> REGISTERED = Collections.newSetFromMap(new IdentityHashMap<>());

    private NativeOitPipelines() {
    }

    /**
     * Render thread; registers a declaration for this resource generation's configuration warmup.
     */
    public static void register(RenderPipeline pipeline) {
        RenderSystem.assertOnRenderThread();
        if (NativeOitProducers.isProducer(pipeline.getShaderDefines())) REGISTERED.add(pipeline);
    }

    public static void register(OitPipelineSet pipelines) {
        register(pipelines.depthBoundsPipeline());
        register(pipelines.transmittancePipeline());
        register(pipelines.accumulatePipeline());
    }

    /**
     * Snapshot for configuration-boundary warmup; optional foreign declarations belong to this generation.
     */
    public static List<RenderPipeline> registeredPipelines() {
        RenderSystem.assertOnRenderThread();
        for (RenderPipeline pipeline : RenderPipelines.requiredPipelines()) register(pipeline);
        return List.copyOf(REGISTERED);
    }

    /**
     * Declares the selected algorithm inside the owning {@link RenderPipelineCompiler#warm} batch. A null
     * insert mode selects wavelet. Optional native pipelines remain demand-driven, as they may be unsupported.
     */
    public static void precompileSelected(@Nullable OitInsertMode insert) {
        RenderSystem.assertOnRenderThread();
        if (NativeOitScope.active() != null) {
            throw new IllegalStateException("Cannot warm native OIT pipelines during producer replay");
        }
        for (RenderPipeline pipeline : RenderPipelines.requiredPipelines()) register(pipeline);
        // Vanilla loads these required utilities. A capability-recovery cache clear can close them again,
        // so the configuration boundary also restores the exact utilities used by native replay.
        RenderSystem.getCompiledPipeline(RenderPipelines.BLIT_DEPTH);
        RenderSystem.getCompiledPipeline(RenderPipelines.BLIT_DEPTH_BOUNDS);
        RenderSystem.getCompiledPipeline(RenderPipelines.OIT_DEPTH_BOUNDS_CULL);
        RenderSystem.getCompiledPipeline(RenderPipelines.BLIT_DEPTH_DURING_DEPTH_BOUNDS);
        if (insert == null) {
            for (RenderPipeline pipeline : REGISTERED) {
                OitMode stage = NativeOitProducers.stage(pipeline.getShaderDefines());
                if (stage == OitMode.DEPTH_RANGE) precompile(pipeline, Variant.DEPTH);
                else if (stage == OitMode.GENERATE_COEFFICIENTS) precompile(pipeline, Variant.COEFFICIENTS);
                else if (stage == OitMode.EVALUATE) {
                    precompile(pipeline, Variant.ACCUMULATE);
                    // The prepared native callback also replays ordinary draws during the emission pass;
                    // their stage-specific wrappers discard before running the original fragment main.
                    precompile(pipeline, Variant.EMISSION);
                }
            }
        } else {
            // Insert preparation preserves native opacity/water/cloud depth with an unadapted depth pass.
            // Foreign sets may have been declared after vanilla's shader loader enumerated required pipelines.
            for (RenderPipeline pipeline : REGISTERED) {
                if (NativeOitProducers.stage(pipeline.getShaderDefines()) == OitMode.DEPTH_RANGE) {
                    RenderSystem.getCompiledPipeline(pipeline);
                }
            }
            RenderPipelineCompiler.feature(ProgramAvailability.insert(insert), () -> {
                Variant variant = Variant.insert(insert);
                for (RenderPipeline pipeline : REGISTERED) {
                    if (NativeOitProducers.stage(pipeline.getShaderDefines()) == OitMode.EVALUATE) {
                        precompile(pipeline, variant);
                    }
                }
            });
        }
    }

    private static void precompile(RenderPipeline base, Variant variant) {
        AdaptedPipeline pipeline = adapted(base, variant);
        RenderPipelineCompiler.precompile(pipeline, pipeline.source);
    }

    static @Nullable CompiledRenderPipeline select(NativeOitScope scope, RenderPipeline pipeline) {
        if (!NativeOitProducers.isProducer(pipeline.getShaderDefines())) return null;
        scope.variant.requireStage(pipeline);
        NativeOitScope.selected(NativeOitProducers.defined(pipeline.getShaderDefines(), NativeOitProducers.ADDITIVE));
        AdaptedPipeline adapted = adapted(pipeline, scope.variant);
        return RenderPipelineCompiler.compile(adapted, adapted.source);
    }

    private static AdaptedPipeline adapted(RenderPipeline pipeline, Variant variant) {
        var overlays = TerrainOverlayPipelines.requestedSources();
        var pipelines = overlays.isEmpty() ? PIPELINES
                : OVERLAY_PIPELINES.computeIfAbsent(overlays, _ -> new IdentityHashMap<>());
        var variants = pipelines.get(pipeline);
        if (variants == null) {
            REGISTERED.add(pipeline);
            variants = new EnumMap<>(Variant.class);
            pipelines.put(pipeline, variants);
        }
        var adapted = variants.get(variant);
        if (adapted == null) {
            adapted = new AdaptedPipeline(pipeline, variant, overlays);
            variants.put(variant, adapted);
        }
        return adapted;
    }

    /**
     * Drops declarations and source bytes after the owning native pipeline cache has been invalidated.
     */
    public static void reset() {
        PIPELINES.clear();
        OVERLAY_PIPELINES.clear();
        REGISTERED.clear();
        NativeOitProducers.reset();
    }

    private static List<BindGroupLayout> layouts(RenderPipeline base, Variant variant, List<Identifier> overlays) {
        List<BindGroupLayout> result =
                NativeOitProducers.layouts(base, NativeOitProducers.NATIVE_READS::contains, true);
        if (variant == Variant.COEFFICIENTS || variant == Variant.ACCUMULATE || variant == Variant.EMISSION) {
            var reads = BindGroupLayout.builder()
                                       .withUniform("_flw_depthRange", UniformType.COMBINED_IMAGE_SAMPLER)
                                       .withUniform("_flw_blueNoise", UniformType.COMBINED_IMAGE_SAMPLER);
            if (variant != Variant.COEFFICIENTS && !OitConfig.coefficientArray()) {
                for (int i = 0; i < 4; i++) {
                    reads.withUniform("_flw_coefficients" + i, UniformType.COMBINED_IMAGE_SAMPLER);
                }
            }
            result.add(reads.build());
        }
        result.addAll(TerrainOverlayPipelines.layouts(overlays));
        return result;
    }

    private static ColorTargetState[] colors(Variant variant) {
        return switch (variant) {
            case DEPTH -> new ColorTargetState[]{DEPTH};
            case COEFFICIENTS -> new ColorTargetState[]{ADDITIVE, ADDITIVE, ADDITIVE, ADDITIVE};
            case ACCUMULATE, EMISSION -> new ColorTargetState[]{ADDITIVE};
            case KBUFFER, MLAB, ABUFFER -> new ColorTargetState[]{NO_COLOR};
            case ORIGINAL -> throw new IllegalArgumentException("GL adapts native producers only");
        };
    }

    private static String assemble(Identifier id, ShaderType type, Variant variant, boolean sodiumPushConstants,
                                   List<Identifier> overlays, String cutoff) {
        Compilation compilation = new Compilation();
        // Insert producers need SSBOs; all native vertex layouts and stage defines remain unchanged.
        compilation.version(ShaderAssembly.glslVersion());
        compilation.polyfillFmaIfMissing();
        if (type == ShaderType.FRAGMENT) {
            NativeOitProducers.assembleFragment(compilation, variant, false, OitConfig.coefficientArray(), id, overlays,
                    cutoff);
            return compilation.assembledSource();
        }
        // Native SPIR-V vertex indices and GL vertex IDs both include the indexed draw's base vertex.
        compilation.define("gl_VertexIndex", "gl_VertexID");
        if (!overlays.isEmpty()) {
            compilation.define("main", "_flw_terrainVertexBase");
            compilation.define("_FLW_TERRAIN_EXPLICIT_VARYINGS");
        }
        if (sodiumPushConstants) {
            // Native GL compilation lowers this same push-constant block to a std140 UBO. Keep Sodium's
            // offsets (vec3 at 0, int at 12, uint at 16) and the frontend's original 20-byte push contract.
            GlPushConstantProgram.configure(compilation, false);
        }
        compilation.appendComponent(
                new ShaderAssembly.RawSource(id.toString(), NativeOitProducers.rawShader(id, type)));
        if (!overlays.isEmpty()) ShaderAssembly.appendComponents(compilation, TerrainOverlayPipelines.vertexTail());
        return compilation.assembledSource();
    }

    private static final class AdaptedPipeline extends RenderPipeline implements GlPipelineProgram {
        private final AssembledShaderSource source;
        private final boolean sodiumPushConstants;

        private AdaptedPipeline(RenderPipeline base, Variant variant, List<Identifier> overlays) {
            super(TerrainOverlayPipelines.withSources(NativeOitProducers.location("native_oit", variant, base),
                            overlays), base.getShaders(),
                    base.getShaderDefines(), layouts(base, variant, overlays), colors(variant),
                    base.getDepthStencilState(), base.getPolygonMode(), base.isCull(),
                    base.getVertexFormatBindings().toArray(VertexFormat[]::new), base.getPrimitiveTopology(),
                    base.pushConstantSize(), base.getSortKey());
            sodiumPushConstants = GlPushConstantProgram.supports(base);
            var cutoff = base.getShaderDefines().values().getOrDefault("ALPHA_CUTOUT", "0.0");
            source = (id, type) -> assemble(id, type, variant, sodiumPushConstants, overlays, cutoff);
        }

        @Override
        public boolean supportsGlPushConstants() {
            return sodiumPushConstants;
        }

        @Override
        public GlProgram createGlProgram(int handle) {
            return sodiumPushConstants ? new GlPushConstantProgram(handle, getLocation().toString()) : new GlProgram(
                    handle, getLocation().toString());
        }
    }
}
