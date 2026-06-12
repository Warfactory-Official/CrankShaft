package dev.engine_room.flywheel.backend.engine.indirect;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.backend.BackendConfig;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.*;
import dev.engine_room.flywheel.backend.engine.BerFamily;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.engine.uniform.FrameUniforms;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.impl.CompactChunkVertex;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.*;

/**
 * {@link RenderPipeline}s for the OIT passes: per-(type, mode) producers plus fullscreen composite/depth passes.
 */
public final class OitPipelines {
    private static final String INSTANCE_BUFFER_NAME = "_flw_instances";

    // GL_MAX over (ONE*src, ONE*dst) == componentwise max -- the depthRange pass. ADDITIVE == ONE/ONE.
    private static final BlendFunction MAX_BLEND = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX,
            BlendFactor.ONE, BlendFactor.ONE, BlendOp.MAX);
    // composite: src.rgb*src.a + dst.rgb*(1-src.a); alpha = src.a + dst.a*(1-src.a).
    private static final BlendFunction COMPOSITE_BLEND = new BlendFunction(BlendFactor.SRC_ALPHA,
            BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA);

    // Reversed-Z depth test against the opaque depth, never write -- shared by every fixed-format OIT producer.
    private static final DepthStencilState OIT_PRODUCER_DEPTH_STATE = new DepthStencilState(
            CompareOp.GREATER_THAN_OR_EQUAL, false, 0.0f, 0.0f);

    private static final Identifier FULLSCREEN_VERTEX = ResourceUtil.rl("codegen/oit/fullscreen");
    private static final Identifier COMPOSITE_FRAGMENT = ResourceUtil.rl("codegen/oit/composite_frag");
    private static final Identifier DEPTH_FRAGMENT = ResourceUtil.rl("codegen/oit/depth_frag");

    private static final String LIGHT_LUT_NAME = "_flw_lightLut";
    private static final String LIGHT_SECTIONS_NAME = "_flw_lightSections";
    private static final Map<Identifier, ProducerFragmentKey> PRODUCER_FRAGMENT_KEY = new HashMap<>();
    private static final Map<Identifier, VertexAssembly> VERTEX_ASSEMBLY = new HashMap<>();
    private static final Identifier CHUNK_VERTEX = ResourceUtil.rl("codegen/oit/chunk");
    private static final Map<Identifier, ChunkFragmentKey> CHUNK_FRAGMENT_KEY = new HashMap<>();
    private static final Identifier CHUNK_SODIUM_VERTEX = ResourceUtil.rl("codegen/oit/chunk_sodium");
    private static final Identifier CHUNK_SODIUM_VERTEX_MDI = ResourceUtil.rl("codegen/oit/chunk_sodium_mdi");
    private static final Identifier CHUNK_SODIUM_VERTEX_MDI_FADE = ResourceUtil.rl("codegen/oit/chunk_sodium_mdi_fade");
    private static final Identifier[] BER_VERTEX = new Identifier[BerFamily.VALUES.length];
    private static final Map<Identifier, BerFamily> BER_VERTEX_FAMILY = new HashMap<>();
    private static final Identifier[][] BER_FRAGMENT = new Identifier[BerFamily.VALUES.length][OitMode.values().length];
    private static final Map<Identifier, BerKey> BER_FRAGMENT_KEY = new HashMap<>();
    private static final Identifier[] LAYER_FRAGMENT = new Identifier[OitMode.values().length];
    private static final Map<Identifier, OitMode> LAYER_FRAGMENT_MODE = new HashMap<>();
    private static final Identifier WEATHER_VERTEX = ResourceUtil.rl("codegen/oit/weather");
    private static final Identifier[] WEATHER_FRAGMENT = new Identifier[OitMode.values().length];
    private static final Map<Identifier, OitMode> WEATHER_FRAGMENT_MODE = new HashMap<>();
    private static final Map<Identifier, MlabUberKey> MLAB_UBER_KEY = new HashMap<>();
    private static final Map<Identifier, ChunkMlabKey> CHUNK_MLAB_KEY = new HashMap<>();
    private static final Map<Identifier, OitInsertMode> MLAB_RESOLVE_MODE = new HashMap<>();
    private static final Map<Identifier, BerMlabKey> BER_MLAB_KEY = new HashMap<>();
    private static final Map<Identifier, OitInsertMode> WEATHER_MLAB_MODE = new HashMap<>();
    private static final ShaderSource SHADER_SOURCE = (id, type) -> switch (type) {
        case VERTEX -> {
            if (id.equals(FULLSCREEN_VERTEX)) {
                yield RenderPassShaders.fullscreenVertex();
            }
            if (id.equals(CHUNK_VERTEX)) {
                yield RenderPassShaders.assembleChunkOitVertex();
            }
            if (id.equals(CHUNK_SODIUM_VERTEX)) {
                yield RenderPassShaders.assembleSodiumChunkOitVertex(false, false);
            }
            if (id.equals(CHUNK_SODIUM_VERTEX_MDI)) {
                yield RenderPassShaders.assembleSodiumChunkOitVertex(true, false);
            }
            if (id.equals(CHUNK_SODIUM_VERTEX_MDI_FADE)) {
                yield RenderPassShaders.assembleSodiumChunkOitVertex(true, true);
            }
            BerFamily berFamily = BER_VERTEX_FAMILY.get(id);
            if (berFamily != null) {
                yield RenderPassShaders.assembleBerOitVertex(berFamily);
            }
            if (id.equals(WEATHER_VERTEX)) {
                yield RenderPassShaders.assembleWeatherOitVertex();
            }
            VertexAssembly va = VERTEX_ASSEMBLY.get(id);
            if (id.getPath().contains("/uber/")) {
                yield RenderPassShaders.assembleUberIndirectVertex(va.material(), va.debug(),
                        RenderPassShaders.maybeBindlessGl());
            }
            boolean indirect = id.getPath().contains("/indirect/");
            if (va.embedded()) {
                yield indirect
                        ? RenderPassShaders.assembleIndirectEmbeddedVertex(va.type(), va.material(), va.debug(),
                        RenderPassShaders.maybeBindlessGl())
                        : RenderPassShaders.assembleInstancingEmbeddedVertex(va.type(), va.material(), va.debug());
            }
            yield indirect
                    ? RenderPassShaders.assembleIndirectVertex(va.type(), va.material(), va.debug(),
                    RenderPassShaders.maybeBindlessGl())
                    : RenderPassShaders.assembleInstancingVertex(va.type(), va.material(), va.debug());
        }
        case FRAGMENT -> {
            if (id.equals(COMPOSITE_FRAGMENT)) {
                yield RenderPassShaders.assembleOitComposite();
            }
            if (id.equals(DEPTH_FRAGMENT)) {
                yield RenderPassShaders.assembleOitDepth();
            }
            ChunkFragmentKey chunkKey = CHUNK_FRAGMENT_KEY.get(id);
            if (chunkKey != null) {
                yield RenderPassShaders.assembleChunkOitFragment(chunkKey.mode(), chunkKey.linear());
            }
            BerKey berKey = BER_FRAGMENT_KEY.get(id);
            if (berKey != null) {
                yield RenderPassShaders.assembleBerOitFragment(berKey.family(), berKey.mode());
            }
            OitMode layerMode = LAYER_FRAGMENT_MODE.get(id);
            if (layerMode != null) {
                yield RenderPassShaders.assembleLayerOitFragment(layerMode);
            }
            OitMode weatherMode = WEATHER_FRAGMENT_MODE.get(id);
            if (weatherMode != null) {
                yield RenderPassShaders.assembleWeatherOitFragment(weatherMode);
            }
            MlabUberKey mlabUber = MLAB_UBER_KEY.get(id);
            if (mlabUber != null) {
                yield RenderPassShaders.assembleUberMlabFragment(mlabUber.mode(), mlabUber.light(), mlabUber.material(),
                        mlabUber.smoothness(), mlabUber.debug(), RenderPassShaders.maybeBindlessGl());
            }
            ChunkMlabKey mlabChunk = CHUNK_MLAB_KEY.get(id);
            if (mlabChunk != null) {
                yield RenderPassShaders.assembleChunkMlabFragment(mlabChunk.mode(), mlabChunk.linear());
            }
            OitInsertMode mlabResolveMode = MLAB_RESOLVE_MODE.get(id);
            if (mlabResolveMode != null) {
                yield RenderPassShaders.assembleMlabResolve(mlabResolveMode);
            }
            BerMlabKey berMlab = BER_MLAB_KEY.get(id);
            if (berMlab != null) {
                yield RenderPassShaders.assembleBerMlabFragment(berMlab.family(), berMlab.mode());
            }
            OitInsertMode weatherMlabMode = WEATHER_MLAB_MODE.get(id);
            if (weatherMlabMode != null) {
                yield RenderPassShaders.assembleWeatherMlabFragment(weatherMlabMode);
            }
            ProducerFragmentKey pfk = PRODUCER_FRAGMENT_KEY.get(id);
            if (id.getPath().contains("/uber/")) {
                yield RenderPassShaders.assembleUberOitFragment(pfk.mode(), pfk.light(), pfk.material(),
                        pfk.smoothness(), pfk.debug(), RenderPassShaders.maybeBindlessGl());
            }
            yield RenderPassShaders.assembleOitFragment(pfk.mode(), pfk.light(), pfk.indirect(), pfk.material(),
                    pfk.smoothness(), pfk.cutout(), pfk.fog(), pfk.debug(),
                    pfk.indirect() ? RenderPassShaders.maybeBindlessGl() : ShaderAssembly.NO_EXTRA);
        }
    };
    private static final Map<ProducerKey, RenderPipeline> PRODUCER_CACHE = new HashMap<>();
    private static final Map<ChunkFragmentKey, RenderPipeline> CHUNK_CACHE = new HashMap<>();
    private static final Map<ChunkFragmentKey, RenderPipeline> CHUNK_SODIUM_CACHE = new HashMap<>();
    private static final Map<ChunkFragmentKey, RenderPipeline> CHUNK_SODIUM_SETTLED_CACHE = new HashMap<>();
    private static final Map<ChunkFragmentKey, RenderPipeline> CHUNK_SODIUM_FADING_CACHE = new HashMap<>();
    private static final Map<BerKey, RenderPipeline> BER_CACHE = new HashMap<>();
    private static final Map<OitMode, RenderPipeline> LAYER_CACHE = new EnumMap<>(OitMode.class);
    private static final Map<OitMode, RenderPipeline> WEATHER_CACHE = new EnumMap<>(OitMode.class);
    private static final Map<UberProducerKey, RenderPipeline> UBER_PRODUCER_CACHE = new HashMap<>();
    private static final BlendFunction PREMULT_BLEND = new BlendFunction(BlendFactor.ONE,
            BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA);
    private static final ColorTargetState MLAB_NO_COLOR = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
            ColorTargetState.WRITE_NONE);
    private static final Map<MlabUberKey, RenderPipeline> UBER_MLAB_CACHE = new HashMap<>();
    private static final Map<ChunkMlabKey, RenderPipeline> CHUNK_MLAB_CACHE = new HashMap<>();
    private static final Map<OitInsertMode, RenderPipeline> MLAB_RESOLVE_CACHE = new EnumMap<>(OitInsertMode.class);
    private static final Map<BerMlabKey, RenderPipeline> BER_MLAB_CACHE = new HashMap<>();
    private static final Map<OitInsertMode, RenderPipeline> WEATHER_MLAB_CACHE = new EnumMap<>(OitInsertMode.class);
    private static final Map<ChunkSodiumMlabKey, RenderPipeline> CHUNK_SODIUM_MLAB_CACHE = new HashMap<>();
    private static RenderPipeline compositePipeline;
    private static RenderPipeline depthPipeline;

    static {
        for (BerFamily family : BerFamily.VALUES) {
            Identifier vertexId = ResourceUtil.rl("codegen/oit/ber" + family.suffix);
            BER_VERTEX[family.ordinal()] = vertexId;
            BER_VERTEX_FAMILY.put(vertexId, family);
            for (OitMode mode : new OitMode[]{OitMode.DEPTH_RANGE, OitMode.GENERATE_COEFFICIENTS, OitMode.EVALUATE}) {
                Identifier id = ResourceUtil.rl("codegen/oit/ber_frag" + family.suffix + mode.name);
                BER_FRAGMENT[family.ordinal()][mode.ordinal()] = id;
                BER_FRAGMENT_KEY.put(id, new BerKey(family, mode));
            }
        }
    }

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

    private OitPipelines() {
    }

    private static Identifier chunkFragmentId(OitMode mode, boolean linear) {
        Identifier id = ResourceUtil.rl("codegen/oit/chunk_frag" + mode.name + (linear ? "_linear" : ""));
        CHUNK_FRAGMENT_KEY.putIfAbsent(id, new ChunkFragmentKey(mode, linear));
        return id;
    }

    public static RenderPipeline producer(Material material, InstanceType<?> instanceType, OitMode mode) {
        return producer(material, instanceType, mode, false, false);
    }

    public static RenderPipeline producer(Material material, InstanceType<?> instanceType, OitMode mode,
                                          boolean indirect) {
        return producer(material, instanceType, mode, indirect, false);
    }

    public static RenderPipeline producer(Material material, InstanceType<?> instanceType, OitMode mode,
                                          boolean indirect, boolean embedded) {
        MaterialShaders shaders = material.shaders();
        LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        DebugMode debug = FrameUniforms.debugMode();
        Identifier vertexId = vertexId(instanceType, indirect, shaders, embedded, debug != DebugMode.OFF);
        VERTEX_ASSEMBLY.putIfAbsent(vertexId,
                new VertexAssembly(instanceType, shaders, embedded, debug != DebugMode.OFF));
        LightShader light = material.light();
        CutoutShader cutout = material.cutout();
        FogShader fog = material.fog();
        PRODUCER_FRAGMENT_KEY.putIfAbsent(
                producerFragmentId(mode, light, indirect, shaders, smoothness, cutout, fog, debug),
                new ProducerFragmentKey(mode, light, indirect, shaders, smoothness, cutout, fog, debug));

        ProducerKey key = new ProducerKey(instanceType, light, shaders, smoothness, cutout, fog, debug, mode, indirect,
                material.depthTest(),
                material.backfaceCulling(), material.polygonOffset(), embedded);
        RenderPipeline pipeline = PRODUCER_CACHE.computeIfAbsent(key, OitPipelines::buildProducer);
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    public static RenderPipeline uberProducer(Material material, OitMode mode) {
        MaterialShaders shaders = material.shaders();
        LightShader light = material.light();
        LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        DebugMode debug = FrameUniforms.debugMode();

        Identifier vertexId = uberVertexId(shaders, debug != DebugMode.OFF);
        VERTEX_ASSEMBLY.putIfAbsent(vertexId, new VertexAssembly(null, shaders, false, debug != DebugMode.OFF));
        Identifier fragmentId = uberProducerFragmentId(mode, light, shaders, smoothness, debug);
        PRODUCER_FRAGMENT_KEY.putIfAbsent(fragmentId,
                new ProducerFragmentKey(mode, light, true, shaders, smoothness, null, null, debug));

        UberProducerKey key = new UberProducerKey(light, shaders, smoothness, debug, mode, material.depthTest(),
                material.backfaceCulling(), material.polygonOffset(),
                InstanceTypeIds.snapshot().types().size(),
                MaterialShaderIndices.cutoutSources().all().size(),
                MaterialShaderIndices.fogSources().all().size());
        RenderPipeline pipeline = UBER_PRODUCER_CACHE.computeIfAbsent(key, OitPipelines::buildUberProducer);
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static Identifier uberVertexId(MaterialShaders materialShaders, boolean debug) {
        return ResourceUtil.rl("codegen/oit/uber/g" + InstanceTypeIds.snapshot().types().size()
                + "__" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource())
                + (debug ? "__debug" : ""));
    }

    private static Identifier uberProducerFragmentId(OitMode mode, LightShader light, MaterialShaders materialShaders,
                                                     LightSmoothness smoothness, DebugMode debug) {
        return ResourceUtil.rl("codegen/oit/producer_frag/uber/g" + MaterialShaderIndices.cutoutSources().all().size()
                + "_" + MaterialShaderIndices.fogSources().all().size()
                + "__" + ResourceUtil.toDebugFileNameNoExtension(light.source())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                + "__" + smoothness.getSerializedName() + mode.name
                + (debug == DebugMode.OFF ? "" : "__debug_" + debug.getSerializedName()));
    }

    private static RenderPipeline buildUberProducer(UberProducerKey key) {
        OitMode mode = key.mode();
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                       .withLocation(ResourceUtil.rl("pipeline/oit/" + key.cacheName()))
                                                       .withVertexShader(uberVertexId(key.materialShaders(),
                                                               key.debug() != DebugMode.OFF))
                                                       .withFragmentShader(uberProducerFragmentId(mode, key.light(),
                                                               key.materialShaders(), key.smoothness(), key.debug()))
                                                       .withVertexBinding(0, InternalVertex.VERTEX_FORMAT)
                                                       .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                       .withDepthStencilState(
                                                               new DepthStencilState(key.depthTest().compareOp, false,
                                                                       key.polygonOffset() ? 1.0f : 0.0f,
                                                                       key.polygonOffset() ? 10.0f : 0.0f))
                                                       .withCull(key.cull());

        builder.withBindGroupLayout(producerBindGroup(mode, true, false));

        return withOitColorTargets(builder, mode, "OIT").build();
    }

    private static Identifier producerFragmentId(OitMode mode, LightShader light, boolean indirect,
                                                 MaterialShaders materialShaders, LightSmoothness smoothness,
                                                 CutoutShader cutout, FogShader fog, DebugMode debug) {
        return ResourceUtil.rl("codegen/oit/producer_frag/" + (indirect ? "ind_" : "ins_")
                + ResourceUtil.toDebugFileNameNoExtension(light.source())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(cutout.source())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(fog.source())
                + "__" + smoothness.getSerializedName() + mode.name
                + (debug == DebugMode.OFF ? "" : "__debug_" + debug.getSerializedName()));
    }

    public static RenderPipeline composite() {
        if (compositePipeline == null) {
            compositePipeline = buildComposite();
        }
        RenderSystem.getDevice()
                    .precompilePipeline(compositePipeline, SHADER_SOURCE);
        return compositePipeline;
    }

    public static RenderPipeline depth() {
        if (depthPipeline == null) {
            depthPipeline = buildDepth();
        }
        RenderSystem.getDevice()
                    .precompilePipeline(depthPipeline, SHADER_SOURCE);
        return depthPipeline;
    }

    public static RenderPipeline chunkProducer(OitMode mode) {
        ChunkFragmentKey key = new ChunkFragmentKey(mode, TerrainAtlasFilter.linear());
        RenderPipeline pipeline = CHUNK_CACHE.computeIfAbsent(key, OitPipelines::buildChunkProducer);
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildChunkProducer(ChunkFragmentKey key) {
        OitMode mode = key.mode();
        RenderPipeline.Builder builder = RenderPipeline.builder()
                                                       .withLocation(ResourceUtil.rl(
                                                               "pipeline/oit/chunk" + mode.name + (key.linear() ? "_linear" : "")))
                                                       .withVertexShader(CHUNK_VERTEX)
                                                       .withFragmentShader(chunkFragmentId(mode, key.linear()))
                                                       .withVertexBinding(0, DefaultVertexFormat.BLOCK)
                                                       .withPrimitiveTopology(PrimitiveTopology.QUADS)
                                                       .withDepthStencilState(OIT_PRODUCER_DEPTH_STATE)
                                                       .withCull(true)
                                                       .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                                                       .withBindGroupLayout(BindGroupLayouts.FOG)
                                                       .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                                                       .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                                                       .withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION);

        BindGroupLayout oitSamplers = chunkOitSamplers(mode);
        if (oitSamplers != null) {
            builder.withBindGroupLayout(oitSamplers);
        }

        return withOitColorTargets(builder, mode, "Chunk-OIT").build();
    }

    public static RenderPipeline chunkSodiumProducer(OitMode mode) {
        ChunkFragmentKey key = new ChunkFragmentKey(mode, TerrainAtlasFilter.linear());
        RenderPipeline pipeline = CHUNK_SODIUM_CACHE.computeIfAbsent(key,
                k -> buildChunkSodiumProducer(k, CHUNK_SODIUM_VERTEX, "chunk_sodium"));
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    public static RenderPipeline chunkSodiumProducer(OitMode mode, boolean fading) {
        ChunkFragmentKey key = new ChunkFragmentKey(mode, TerrainAtlasFilter.linear());
        Map<ChunkFragmentKey, RenderPipeline> cache = fading ? CHUNK_SODIUM_FADING_CACHE : CHUNK_SODIUM_SETTLED_CACHE;
        Identifier vsh = fading ? CHUNK_SODIUM_VERTEX_MDI_FADE : CHUNK_SODIUM_VERTEX_MDI;
        String nameSuffix = fading ? "chunk_sodium_mdi_fade" : "chunk_sodium_mdi";
        RenderPipeline pipeline = cache.computeIfAbsent(key, k -> buildChunkSodiumProducer(k, vsh, nameSuffix));
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildChunkSodiumProducer(ChunkFragmentKey key, Identifier vertexId,
                                                           String nameSuffix) {
        OitMode mode = key.mode();
        RenderPipeline.Builder builder = RenderPipeline.builder()
                                                       .withLocation(ResourceUtil.rl(
                                                               "pipeline/oit/" + nameSuffix + mode.name + (key.linear() ? "_linear" : "")))
                                                       .withVertexShader(vertexId)
                                                       .withFragmentShader(chunkFragmentId(mode, key.linear()))
                                                       .withVertexBinding(0, CompactChunkVertex.VERTEX_FORMAT)
                                                       .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                       .withDepthStencilState(OIT_PRODUCER_DEPTH_STATE)
                                                       .withCull(true)
                                                       .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                                                       .withBindGroupLayout(BindGroupLayouts.FOG)
                                                       .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                                                       .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                                                       .withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION);

        BindGroupLayout oitSamplers = chunkOitSamplers(mode);
        if (oitSamplers != null) {
            builder.withBindGroupLayout(oitSamplers);
        }

        return withOitColorTargets(builder, mode, "Sodium chunk-OIT").build();
    }

    private static BindGroupLayout.Builder withCoefficientSamplers(BindGroupLayout.Builder b) {
        if (OitConfig.coefficientArray()) {
            return b;
        }
        return b.withSampler("_flw_coefficients0")
                .withSampler("_flw_coefficients1")
                .withSampler("_flw_coefficients2")
                .withSampler("_flw_coefficients3");
    }

    private static BindGroupLayout.Builder withOitReadSamplers(BindGroupLayout.Builder b, OitMode mode) {
        if (mode == OitMode.DEPTH_RANGE) {
            return b;
        }
        b = b.withSampler("_flw_depthRange")
             .withSampler("_flw_blueNoise");
        if (mode == OitMode.EVALUATE) {
            b = withCoefficientSamplers(b);
        }
        return b;
    }

    private static BindGroupLayout chunkOitSamplers(OitMode mode) {
        if (mode == OitMode.DEPTH_RANGE) {
            return null;
        }
        return withOitReadSamplers(BindGroupLayout.builder(), mode).build();
    }

    public static RenderPipeline berProducer(BerFamily family, OitMode mode) {
        RenderPipeline pipeline = BER_CACHE.computeIfAbsent(new BerKey(family, mode), OitPipelines::buildBerProducer);
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildBerProducer(BerKey key) {
        BerFamily family = key.family();
        OitMode mode = key.mode();
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                       .withLocation(ResourceUtil.rl(
                                                               "pipeline/oit/ber" + family.suffix + mode.name))
                                                       .withVertexShader(BER_VERTEX[family.ordinal()])
                                                       .withFragmentShader(
                                                               BER_FRAGMENT[family.ordinal()][mode.ordinal()])
                                                       .withVertexBinding(0, family.format)
                                                       .withPrimitiveTopology(PrimitiveTopology.QUADS)
                                                       .withDepthStencilState(OIT_PRODUCER_DEPTH_STATE)
                                                       .withCull(family.cull)
                                                       .withBindGroupLayout(berOitSamplers(family, mode));

        return withOitColorTargets(builder, mode, "BER-OIT").build();
    }

    public static RenderPipeline layerProducer(OitMode mode) {
        RenderPipeline pipeline = LAYER_CACHE.computeIfAbsent(mode, OitPipelines::buildLayerProducer);
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildLayerProducer(OitMode mode) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                       .withLocation(ResourceUtil.rl("pipeline/oit/layer" + mode.name))
                                                       .withVertexShader(FULLSCREEN_VERTEX)
                                                       .withFragmentShader(LAYER_FRAGMENT[mode.ordinal()])
                                                       .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                       .withDepthStencilState(OIT_PRODUCER_DEPTH_STATE)
                                                       .withCull(false)
                                                       .withBindGroupLayout(layerBindGroup(mode));

        return withOitColorTargets(builder, mode, "Layer-OIT").build();
    }

    private static BindGroupLayout layerBindGroup(OitMode mode) {
        return withOitReadSamplers(BindGroupLayout.builder()
                                                  .withSampler("_flw_layerColor")
                                                  .withSampler("_flw_layerDepth"), mode).build();
    }

    public static RenderPipeline weatherProducer(OitMode mode) {
        RenderPipeline pipeline = WEATHER_CACHE.computeIfAbsent(mode, OitPipelines::buildWeatherProducer);
        RenderSystem.getDevice()
                    .precompilePipeline(pipeline, SHADER_SOURCE);
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
                                                       .withDepthStencilState(OIT_PRODUCER_DEPTH_STATE)
                                                       .withCull(false)
                                                       .withBindGroupLayout(weatherOitSamplers(mode));

        return withOitColorTargets(builder, mode, "Weather-OIT").build();
    }

    private static BindGroupLayout weatherOitSamplers(OitMode mode) {
        return withOitReadSamplers(BindGroupLayout.builder()
                                                  .withSampler("Sampler0")
                                                  .withSampler("Sampler2"), mode).build();
    }

    private static RenderPipeline.Builder withOitColorTargets(RenderPipeline.Builder builder, OitMode mode,
                                                              String producerName) {
        switch (mode) {
            case DEPTH_RANGE -> builder.withColorTargetState(0,
                    new ColorTargetState(Optional.of(MAX_BLEND), OitFramebuffer.DEPTH_BOUNDS_FORMAT,
                            ColorTargetState.WRITE_ALL));
            case GENERATE_COEFFICIENTS -> {
                for (int i = 0; i < 4; i++) {
                    builder.withColorTargetState(i, new ColorTargetState(Optional.of(BlendFunction.ADDITIVE),
                            OitFramebuffer.COEFFICIENTS_FORMAT, ColorTargetState.WRITE_ALL));
                }
            }
            case EVALUATE -> builder.withColorTargetState(0,
                    new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), OitFramebuffer.ACCUMULATE_FORMAT,
                            ColorTargetState.WRITE_ALL));
            default ->
                    throw new IllegalArgumentException(producerName + " producer requires a non-OFF mode, got " + mode);
        }
        return builder;
    }

    private static BindGroupLayout berOitSamplers(BerFamily family, OitMode mode) {
        BindGroupLayout.Builder builder = BindGroupLayout.builder()
                                                         .withSampler("Sampler0");
        if (family.overlay) {
            builder.withSampler("Sampler1");
        }
        if (family.lightmap) {
            builder.withSampler("Sampler2");
        }
        return withOitReadSamplers(builder, mode).build();
    }

    private static Identifier vertexId(InstanceType<?> instanceType, boolean indirect, MaterialShaders materialShaders,
                                       boolean embedded, boolean debug) {
        return ResourceUtil.rl("codegen/oit/" + (indirect ? "indirect/" : "instancing/") + (embedded ? "embedded/" : "")
                + ResourceUtil.toDebugFileNameNoExtension(instanceType.vertexShader())
                + "__" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource())
                + (debug ? "__debug" : ""));
    }

    private static RenderPipeline buildProducer(ProducerKey key) {
        OitMode mode = key.mode();
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                       .withLocation(ResourceUtil.rl("pipeline/oit/" + key.cacheName()))
                                                       .withVertexShader(vertexId(key.instanceType(), key.indirect(),
                                                               key.materialShaders(), key.embedded(),
                                                               key.debug() != DebugMode.OFF))
                                                       .withFragmentShader(
                                                               producerFragmentId(mode, key.light(), key.indirect(),
                                                                       key.materialShaders(), key.smoothness(),
                                                                       key.cutout(), key.fog(), key.debug()))
                                                       .withVertexBinding(0, InternalVertex.VERTEX_FORMAT)
                                                       .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                       .withDepthStencilState(
                                                               new DepthStencilState(key.depthTest().compareOp, false,
                                                                       key.polygonOffset() ? 1.0f : 0.0f,
                                                                       key.polygonOffset() ? 10.0f : 0.0f))
                                                       .withCull(key.cull());

        builder.withBindGroupLayout(producerBindGroup(mode, key.indirect(), key.embedded()));

        return withOitColorTargets(builder, mode, "OIT").build();
    }

    private static BindGroupLayout producerBindGroup(OitMode mode, boolean indirect, boolean embedded) {
        BindGroupLayout.Builder b = BindGroupLayout.builder();
        if (mode == OitMode.DEPTH_RANGE) {
            if (!indirect) {
                b.withUniform(INSTANCE_BUFFER_NAME, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT);
                b.withUniform("_FlwInstanceDraw", UniformType.UNIFORM_BUFFER);
            }
        } else {
            if (!(indirect && GlCompat.SUPPORTS_BINDLESS_TEXTURES)) {
                b.withSampler("Sampler0");
            }
            b.withSampler("Sampler1")
             .withSampler("Sampler2");
            if (!indirect) {
                b.withUniform(INSTANCE_BUFFER_NAME, UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
                 .withUniform(LIGHT_LUT_NAME, UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
                 .withUniform(LIGHT_SECTIONS_NAME, UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT);
            }
            withOitReadSamplers(b, mode);
            if (!indirect) {
                b.withUniform("_FlwInstanceDraw", UniformType.UNIFORM_BUFFER);
            }
            b.withUniform("_FlwRenderOrigin", UniformType.UNIFORM_BUFFER);
        }
        if (embedded && !indirect) {
            b.withUniform("_FlwEmbed", UniformType.UNIFORM_BUFFER);
        }
        return b.build();
    }

    private static RenderPipeline buildComposite() {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             .withLocation(ResourceUtil.rl("pipeline/oit/composite"))
                             .withVertexShader(FULLSCREEN_VERTEX)
                             .withFragmentShader(COMPOSITE_FRAGMENT)
                             .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                             .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true, 0.0f, 0.0f))
                             .withCull(false)
                             .withBindGroupLayout(withCoefficientSamplers(BindGroupLayout.builder()
                                                                                         .withSampler("_flw_accumulate")
                                                                                         .withSampler(
                                                                                                 "_flw_depthRange"))
                                     .build())
                             .withColorTargetState(0,
                                     new ColorTargetState(Optional.of(COMPOSITE_BLEND), GpuFormat.RGBA8_UNORM,
                                             ColorTargetState.WRITE_ALL))
                             .build();
    }

    private static RenderPipeline buildDepth() {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             .withLocation(ResourceUtil.rl("pipeline/oit/depth"))
                             .withVertexShader(FULLSCREEN_VERTEX)
                             .withFragmentShader(DEPTH_FRAGMENT)
                             .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                             .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true, 0.0f, 0.0f))
                             .withCull(false)
                             .withBindGroupLayout(withCoefficientSamplers(BindGroupLayout.builder()
                                                                                         .withSampler(
                                                                                                 "_flw_depthRange"))
                                     .build())
                             .withColorTargetState(0,
                                     new ColorTargetState(Optional.empty(), OitFramebuffer.ACCUMULATE_FORMAT,
                                             ColorTargetState.WRITE_NONE))
                             .build();
    }

    private static Identifier uberMlabFragmentId(OitInsertMode mode, LightShader light, MaterialShaders m,
                                                 LightSmoothness s, DebugMode debug) {
        Identifier id = ResourceUtil.rl(
                "codegen/oit/mlab/uber_frag/g" + MaterialShaderIndices.cutoutSources().all().size()
                        + "_" + MaterialShaderIndices.fogSources().all().size()
                        + "__" + ResourceUtil.toDebugFileNameNoExtension(light.source())
                        + "__" + ResourceUtil.toDebugFileNameNoExtension(m.fragmentSource())
                        + "__" + s.getSerializedName() + "_" + mode.name().toLowerCase(Locale.ROOT)
                        + (debug == DebugMode.OFF ? "" : "__debug_" + debug.getSerializedName()));
        return id;
    }

    private static Identifier chunkMlabFragmentId(OitInsertMode mode, boolean linear) {
        Identifier id = ResourceUtil.rl(
                "codegen/oit/mlab/chunk_frag_" + mode.name().toLowerCase(Locale.ROOT) + (linear ? "_linear" : ""));
        CHUNK_MLAB_KEY.putIfAbsent(id, new ChunkMlabKey(mode, linear));
        return id;
    }

    private static Identifier mlabResolveFragmentId(OitInsertMode mode) {
        Identifier id = ResourceUtil.rl("codegen/oit/mlab/resolve_frag_" + mode.name().toLowerCase(Locale.ROOT));
        MLAB_RESOLVE_MODE.putIfAbsent(id, mode);
        return id;
    }

    public static RenderPipeline uberMlab(Material material, OitInsertMode mode) {
        MaterialShaders shaders = material.shaders();
        LightShader light = material.light();
        LightSmoothness smoothness = BackendConfig.INSTANCE.lightSmoothness();
        DebugMode debug = FrameUniforms.debugMode();
        Identifier vertexId = uberVertexId(shaders, debug != DebugMode.OFF);
        VERTEX_ASSEMBLY.putIfAbsent(vertexId, new VertexAssembly(null, shaders, false, debug != DebugMode.OFF));

        MlabUberKey key = new MlabUberKey(mode, light, shaders, smoothness, debug, material.depthTest(),
                material.backfaceCulling(), material.polygonOffset(),
                InstanceTypeIds.snapshot().types().size(),
                MaterialShaderIndices.cutoutSources().all().size(),
                MaterialShaderIndices.fogSources().all().size());
        MLAB_UBER_KEY.put(uberMlabFragmentId(mode, light, shaders, smoothness, debug), key);
        RenderPipeline pipeline = UBER_MLAB_CACHE.computeIfAbsent(key, OitPipelines::buildUberMlab);
        RenderSystem.getDevice().precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildUberMlab(MlabUberKey key) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                                                       .withLocation(ResourceUtil.rl(
                                                               "pipeline/oit/mlab/uber_" + key.mode().name()
                                                                                              .toLowerCase(Locale.ROOT)
                                                                       + "_" + ResourceUtil.toDebugFileNameNoExtension(
                                                                       key.light().source())
                                                                       + "_" + ResourceUtil.toDebugFileNameNoExtension(
                                                                       key.material().fragmentSource())
                                                                       + "_" + key.smoothness().getSerializedName()
                                                                       + (key.debug() == DebugMode.OFF ? "" : "_debug_" + key.debug()
                                                                                                                             .getSerializedName())))
                                                       .withVertexShader(uberVertexId(key.material(),
                                                               key.debug() != DebugMode.OFF))
                                                       .withFragmentShader(uberMlabFragmentId(key.mode(), key.light(),
                                                               key.material(), key.smoothness(), key.debug()))
                                                       .withVertexBinding(0, InternalVertex.VERTEX_FORMAT)
                                                       .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                                                       .withDepthStencilState(
                                                               new DepthStencilState(key.depthTest().compareOp, false,
                                                                       key.polygonOffset() ? 1.0f : 0.0f,
                                                                       key.polygonOffset() ? 10.0f : 0.0f))
                                                       .withCull(key.cull())
                                                       .withBindGroupLayout(uberMlabBindGroup())
                                                       .withColorTargetState(0, MLAB_NO_COLOR);
        return builder.build();
    }

    private static BindGroupLayout uberMlabBindGroup() {
        BindGroupLayout.Builder b = BindGroupLayout.builder();
        if (!GlCompat.SUPPORTS_BINDLESS_TEXTURES) {
            b.withSampler("Sampler0");
        }
        b.withSampler("Sampler1")
         .withSampler("Sampler2")
         .withUniform("_FlwRenderOrigin", UniformType.UNIFORM_BUFFER);
        return b.build();
    }

    public static RenderPipeline chunkMlab(OitInsertMode mode) {
        ChunkMlabKey key = new ChunkMlabKey(mode, TerrainAtlasFilter.linear());
        RenderPipeline pipeline = CHUNK_MLAB_CACHE.computeIfAbsent(key, OitPipelines::buildChunkMlab);
        RenderSystem.getDevice().precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildChunkMlab(ChunkMlabKey key) {
        return RenderPipeline.builder()
                             .withLocation(ResourceUtil.rl("pipeline/oit/mlab/chunk_" + key.mode().name().toLowerCase(
                                     Locale.ROOT) + (key.linear() ? "_linear" : "")))
                             .withVertexShader(CHUNK_VERTEX)
                             .withFragmentShader(chunkMlabFragmentId(key.mode(), key.linear()))
                             .withVertexBinding(0, DefaultVertexFormat.BLOCK)
                             .withPrimitiveTopology(PrimitiveTopology.QUADS)
                             .withDepthStencilState(OIT_PRODUCER_DEPTH_STATE)
                             .withCull(true)
                             .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                             .withBindGroupLayout(BindGroupLayouts.FOG)
                             .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                             .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                             .withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION)
                             .withColorTargetState(0, MLAB_NO_COLOR)
                             .build();
    }

    public static RenderPipeline mlabResolve(OitInsertMode mode) {
        RenderPipeline pipeline = MLAB_RESOLVE_CACHE.computeIfAbsent(mode, OitPipelines::buildMlabResolve);
        RenderSystem.getDevice().precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildMlabResolve(OitInsertMode mode) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             .withLocation(ResourceUtil.rl(
                                     "pipeline/oit/mlab/resolve_" + mode.name().toLowerCase(Locale.ROOT)))
                             .withVertexShader(FULLSCREEN_VERTEX)
                             .withFragmentShader(mlabResolveFragmentId(mode))
                             .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                             .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true, 0.0f, 0.0f))
                             .withCull(false)
                             .withBindGroupLayout(BindGroupLayout.builder()
                                                                 .withSampler("_flw_layerColor")
                                                                 .withSampler("_flw_layerDepth")
                                                                 .withSampler("_flw_layerColor1")
                                                                 .withSampler("_flw_layerDepth1")
                                                                 .withSampler("_flw_layerColor2")
                                                                 .withSampler("_flw_layerDepth2")
                                                                 .withSampler("_flw_layerColor3")
                                                                 .withSampler("_flw_layerDepth3")
                                                                 .build())
                             .withColorTargetState(0,
                                     new ColorTargetState(Optional.of(PREMULT_BLEND), GpuFormat.RGBA8_UNORM,
                                             ColorTargetState.WRITE_ALL))
                             .build();
    }

    private static Identifier berMlabFragmentId(BerMlabKey key) {
        Identifier id = ResourceUtil.rl(
                "codegen/oit/mlab/ber_frag" + key.family().suffix + "_" + key.mode().name().toLowerCase(Locale.ROOT));
        BER_MLAB_KEY.putIfAbsent(id, key);
        return id;
    }

    public static RenderPipeline berMlab(BerFamily family, OitInsertMode mode) {
        RenderPipeline pipeline = BER_MLAB_CACHE.computeIfAbsent(new BerMlabKey(family, mode),
                OitPipelines::buildBerMlab);
        RenderSystem.getDevice().precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildBerMlab(BerMlabKey key) {
        BerFamily family = key.family();
        BindGroupLayout.Builder samplers = BindGroupLayout.builder()
                                                          .withSampler("Sampler0");
        if (family.overlay) {
            samplers.withSampler("Sampler1");
        }
        if (family.lightmap) {
            samplers.withSampler("Sampler2");
        }
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             .withLocation(ResourceUtil.rl(
                                     "pipeline/oit/mlab/ber" + family.suffix + "_" + key.mode().name()
                                                                                        .toLowerCase(Locale.ROOT)))
                             .withVertexShader(BER_VERTEX[family.ordinal()])
                             .withFragmentShader(berMlabFragmentId(key))
                             .withVertexBinding(0, family.format)
                             .withPrimitiveTopology(PrimitiveTopology.QUADS)
                             .withDepthStencilState(OIT_PRODUCER_DEPTH_STATE)
                             .withCull(family.cull)
                             .withBindGroupLayout(samplers.build())
                             .withColorTargetState(0, MLAB_NO_COLOR)
                             .build();
    }

    private static Identifier weatherMlabFragmentId(OitInsertMode mode) {
        Identifier id = ResourceUtil.rl("codegen/oit/mlab/weather_frag_" + mode.name().toLowerCase(Locale.ROOT));
        WEATHER_MLAB_MODE.putIfAbsent(id, mode);
        return id;
    }

    public static RenderPipeline weatherMlab(OitInsertMode mode) {
        RenderPipeline pipeline = WEATHER_MLAB_CACHE.computeIfAbsent(mode, OitPipelines::buildWeatherMlab);
        RenderSystem.getDevice().precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildWeatherMlab(OitInsertMode mode) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
                             .withLocation(ResourceUtil.rl(
                                     "pipeline/oit/mlab/weather_" + mode.name().toLowerCase(Locale.ROOT)))
                             .withVertexShader(WEATHER_VERTEX)
                             .withFragmentShader(weatherMlabFragmentId(mode))
                             .withVertexBinding(0, DefaultVertexFormat.PARTICLE)
                             .withPrimitiveTopology(PrimitiveTopology.QUADS)
                             .withDepthStencilState(OIT_PRODUCER_DEPTH_STATE)
                             .withCull(false)
                             .withBindGroupLayout(BindGroupLayout.builder()
                                                                 .withSampler("Sampler0")
                                                                 .withSampler("Sampler2")
                                                                 .build())
                             .withColorTargetState(0, MLAB_NO_COLOR)
                             .build();
    }

    public static RenderPipeline chunkSodiumMlab(OitInsertMode mode, boolean fading) {
        ChunkSodiumMlabKey key = new ChunkSodiumMlabKey(mode, fading, TerrainAtlasFilter.linear());
        RenderPipeline pipeline = CHUNK_SODIUM_MLAB_CACHE.computeIfAbsent(key, OitPipelines::buildChunkSodiumMlab);
        RenderSystem.getDevice().precompilePipeline(pipeline, SHADER_SOURCE);
        return pipeline;
    }

    private static RenderPipeline buildChunkSodiumMlab(ChunkSodiumMlabKey key) {
        Identifier vertexId = key.fading() ? CHUNK_SODIUM_VERTEX_MDI_FADE : CHUNK_SODIUM_VERTEX_MDI;
        return RenderPipeline.builder()
                             .withLocation(
                                     ResourceUtil.rl("pipeline/oit/mlab/chunk_sodium_" + (key.fading() ? "fade_" : "")
                                             + key.mode().name()
                                                  .toLowerCase(Locale.ROOT) + (key.linear() ? "_linear" : "")))
                             .withVertexShader(vertexId)
                             .withFragmentShader(chunkMlabFragmentId(key.mode(), key.linear()))
                             .withVertexBinding(0, CompactChunkVertex.VERTEX_FORMAT)
                             .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                             .withDepthStencilState(OIT_PRODUCER_DEPTH_STATE)
                             .withCull(true)
                             .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                             .withBindGroupLayout(BindGroupLayouts.FOG)
                             .withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
                             .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                             .withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION)
                             .withColorTargetState(0, MLAB_NO_COLOR)
                             .build();
    }

    private record ProducerFragmentKey(OitMode mode, LightShader light, boolean indirect, MaterialShaders material,
                                       LightSmoothness smoothness, CutoutShader cutout, FogShader fog,
                                       DebugMode debug) {
    }

    private record VertexAssembly(InstanceType<?> type, MaterialShaders material, boolean embedded, boolean debug) {
    }

    private record ChunkFragmentKey(OitMode mode, boolean linear) {
    }

    private record BerKey(BerFamily family, OitMode mode) {
    }

    private record UberProducerKey(LightShader light, MaterialShaders materialShaders, LightSmoothness smoothness,
                                   DebugMode debug, OitMode mode, DepthTest depthTest, boolean cull,
                                   boolean polygonOffset,
                                   int typeGen, int cutoutGen, int fogGen) {
        String cacheName() {
            return "uber_" + ResourceUtil.toDebugFileNameNoExtension(light.source())
                    + "_" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource())
                    + "_" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                    + "_" + smoothness.getSerializedName() + mode.name
                    + (debug == DebugMode.OFF ? "" : "_debug_" + debug.getSerializedName())
                    + "_" + depthTest.name().toLowerCase(java.util.Locale.ROOT)
                    + (cull ? "_cull" : "") + (polygonOffset ? "_po" : "");
        }
    }

    private record MlabUberKey(OitInsertMode mode, LightShader light, MaterialShaders material,
                               LightSmoothness smoothness,
                               DebugMode debug, DepthTest depthTest, boolean cull, boolean polygonOffset, int typeGen,
                               int cutoutGen, int fogGen) {
    }

    private record ChunkMlabKey(OitInsertMode mode, boolean linear) {
    }

    private record BerMlabKey(BerFamily family, OitInsertMode mode) {
    }

    private record ChunkSodiumMlabKey(OitInsertMode mode, boolean fading, boolean linear) {
    }

    private record ProducerKey(InstanceType<?> instanceType, LightShader light, MaterialShaders materialShaders,
                               LightSmoothness smoothness, CutoutShader cutout, FogShader fog, DebugMode debug,
                               OitMode mode, boolean indirect, DepthTest depthTest,
                               boolean cull, boolean polygonOffset, boolean embedded) {
        String cacheName() {
            StringBuilder sb = new StringBuilder();
            sb.append(indirect ? "ind_" : "ins_")
              .append(ResourceUtil.toDebugFileNameNoExtension(instanceType.vertexShader()))
              .append('_')
              .append(ResourceUtil.toDebugFileNameNoExtension(light.source()))
              .append('_')
              .append(ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource()))
              .append('_')
              .append(ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource()))
              .append('_')
              .append(ResourceUtil.toDebugFileNameNoExtension(cutout.source()))
              .append('_')
              .append(ResourceUtil.toDebugFileNameNoExtension(fog.source()))
              .append('_')
              .append(smoothness.getSerializedName())
              .append(mode.name)
              .append('_')
              .append(depthTest.name());
            if (debug != DebugMode.OFF) {
                sb.append("_debug_")
                  .append(debug.getSerializedName());
            }
            if (cull) {
                sb.append("_cull");
            }
            if (polygonOffset) {
                sb.append("_po");
            }
            if (embedded) {
                sb.append("_emb");
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        }
    }
}
