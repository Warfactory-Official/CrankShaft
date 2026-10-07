package dev.engine_room.flywheel.backend.compile;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.*;
import dev.engine_room.flywheel.backend.MaterialShaderIndices;
import dev.engine_room.flywheel.backend.OitConfig;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.component.*;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.BerFamily;
import dev.engine_room.flywheel.backend.engine.indirect.InstanceTypeIds;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainAtlasFilter;
import dev.engine_room.flywheel.backend.engine.terrain.TerrainVertexFormat;
import dev.engine_room.flywheel.backend.engine.uniform.DebugMode;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.LightShaders;
import dev.engine_room.flywheel.lib.material.StandardMaterialShaders;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class RenderPassShaders {
    public static final Identifier COLORIZER = ResourceUtil.rl("internal/colorizer.glsl");
    public static final Consumer<Compilation> BINDLESS_GL = ctx -> {
        ctx.requireExtension("GL_ARB_bindless_texture");
        ctx.define("_FLW_BINDLESS_GL");
    };
    public static final Consumer<Compilation> EMISSION_PRODUCER = ctx -> ctx.define("_FLW_OIT_EMISSION");
    public static final Consumer<Compilation> EMBEDDED = ctx -> ctx.define("FLW_EMBEDDED");
    private static final Pattern EMBEDDED_TOKEN = Pattern.compile("\\bFLW_EMBEDDED\\b");
    private static final Map<Identifier, Boolean> READS_EMBEDDED = new HashMap<>();
    private static final String FRAG_LIGHTING_PRELUDE = """
            struct FlwLightAo { vec2 light; float ao; };
            in vec4 flw_vertexPos;
            in vec3 flw_vertexNormal;
            flat in uvec2 _flw_packedMaterial;
            vec4 flw_fragColor;
            vec2 flw_fragLight;
            // Raw atlas sample + vertex colour, kept for a per-material fragment shader (upstream api_impl.frag
            // declares these; a custom fragmentSource may read them). flw_indirect.fsh / flw_oit.fsh set both
            // before flw_materialFragment().
            vec4 flw_sampleColor;
            vec4 flw_vertexColor;
            FlwMaterial flw_material;
            """;
    private static final String FRAG_DIFFUSE_FACTOR = """
            float _flw_diffuseFactor() {
                if (flw_material.cardinalLightingMode == FLW_MAT_CARDINAL_LIGHTING_MODE_ENTITY) {
                    return diffuseFromLightDirections(gl_FrontFacing ? flw_vertexNormal : -flw_vertexNormal);
                } else if (flw_material.cardinalLightingMode == FLW_MAT_CARDINAL_LIGHTING_MODE_CHUNK) {
                    return flw_constantAmbientLight == 1u ? diffuseNether(flw_vertexNormal) : diffuse(flw_vertexNormal);
                } else {
                    return 1.0;
                }
            }
            """;
    private static final String FRAG_CLIP_VARYINGS = """
            in vec2 _flw_clipData;
            """;
    private static @Nullable ShaderSources readsEmbeddedSources;
    private static long registeredReadsEmbeddedGen = -1;
    static final Identifier MLAB = ResourceUtil.rl("internal/mlab.glsl");
    private static final Identifier HEADER = ResourceUtil.rl("renderpass/header.vert");
    private static final Identifier INDIRECT_MAIN = ResourceUtil.rl("renderpass/indirect_main.vert");
    private static final Identifier INSTANCING_MAIN = ResourceUtil.rl("renderpass/instancing_main.vert");
    private static final Identifier FRAGMENT = ResourceUtil.rl("renderpass/flw_indirect.frag");
    private static final Identifier DRAW_COMMAND = ResourceUtil.rl("internal/indirect/draw_command.glsl");
    private static final Identifier MATRICES = ResourceUtil.rl("internal/indirect/matrices.glsl");
    private static final Identifier MATERIAL = ResourceUtil.rl("internal/material.glsl");
    static final Identifier RENDER_ORIGIN = ResourceUtil.rl("internal/render_origin.glsl");
    private static final Identifier PACKED_MATERIAL = ResourceUtil.rl("internal/packed_material.glsl");
    private static final Identifier DIFFUSE = ResourceUtil.rl("internal/diffuse.glsl");
    private static final Identifier INSTANCING_LIGHT = ResourceUtil.rl("internal/instancing/light.glsl");
    private static final Identifier INDIRECT_LIGHT = ResourceUtil.rl("internal/indirect/light.glsl");
    private static final Identifier TEXEL_FILTER = ResourceUtil.rl("internal/texel_filter.glsl");
    private static final Identifier WAVELET = ResourceUtil.rl("internal/wavelet.glsl");
    private static final Identifier OIT_FRAGMENT = ResourceUtil.rl("renderpass/flw_oit.frag");
    private static final Identifier OIT_COMPOSITE = ResourceUtil.rl("internal/oit_composite.frag");
    private static final Identifier OIT_EMISSION = ResourceUtil.rl("internal/oit_emission.frag");
    private static final Identifier OIT_DEPTH = ResourceUtil.rl("internal/oit_depth.frag");
    private static final Identifier FULLSCREEN_VERT = ResourceUtil.rl("internal/fullscreen.vert");
    private static final Identifier MLAB_RESOLVE = ResourceUtil.rl("internal/mlab_resolve.frag");
    private static final Identifier MLAB_NEAREST_DEPTH = ResourceUtil.rl("internal/mlab_nearest_depth.frag");
    private static final Identifier CHUNK_OIT_VERTEX = ResourceUtil.rl("renderpass/chunk_oit.vert");
    private static final Identifier CHUNK_OIT_FRAGMENT = ResourceUtil.rl("renderpass/flw_chunk_oit.frag");
    private static final Identifier CHUNK_OIT_SODIUM_VERTEX = ResourceUtil.rl("renderpass/chunk_oit_sodium.vert");
    private static final Identifier BER_OIT_VERTEX = ResourceUtil.rl("renderpass/ber_oit.vert");
    private static final Identifier BER_OIT_FRAGMENT = ResourceUtil.rl("renderpass/flw_ber_oit.frag");
    private static final Identifier BLOCK_OIT_VERTEX = ResourceUtil.rl("renderpass/block_oit.vert");
    private static final Identifier BLOCK_OIT_FRAGMENT = ResourceUtil.rl("renderpass/flw_block_oit.frag");
    private static final Identifier BEAM_OIT_VERTEX = ResourceUtil.rl("renderpass/beam_oit.vert");
    private static final Identifier BEAM_OIT_FRAGMENT = ResourceUtil.rl("renderpass/flw_beam_oit.frag");
    private static final Identifier LAYER_OIT_FRAGMENT = ResourceUtil.rl("renderpass/flw_layer_oit.frag");
    private static final Identifier WEATHER_OIT_VERTEX = ResourceUtil.rl("renderpass/weather_oit.vert");
    private static final Identifier WEATHER_OIT_FRAGMENT = ResourceUtil.rl("renderpass/flw_weather_oit.frag");
    private static final Consumer<Compilation> INDIRECT_PREAMBLE = ctx -> ctx.requireExtension(
            "GL_ARB_shader_draw_parameters");
    private static final Consumer<Compilation> INSTANCING_PREAMBLE = ctx -> {
    };
    private static final Consumer<Compilation> INSTANCING_VERTEX_PREAMBLE = ctx -> {
        if (GlCompat.USE_INSTANCING_SELECTOR) {
            ctx.define("_FLW_INSTANCING_SELECTOR");
        }
    };
    private static final Consumer<Compilation> INSTANCING_CRUMBLING_PREAMBLE = INSTANCING_VERTEX_PREAMBLE.andThen(ctx -> {
        if (!GlCompat.USE_INSTANCING_SELECTOR) {
            ctx.requireExtension("GL_ARB_shader_draw_parameters");
        }
        ctx.define("_FLW_CRUMBLING");
    });
    private static final Consumer<Compilation> INDIRECT_CRUMBLING_PREAMBLE = INDIRECT_PREAMBLE.andThen(
            ctx -> ctx.define("_FLW_CRUMBLING"));
    private static final Consumer<Compilation> INSTANCING_EMBEDDED_PREAMBLE = INSTANCING_VERTEX_PREAMBLE.andThen(
            ctx -> ctx.define("FLW_EMBEDDED"));
    private static final Consumer<Compilation> INDIRECT_EMBEDDED_PREAMBLE = INDIRECT_PREAMBLE.andThen(
            ctx -> ctx.define("FLW_EMBEDDED"));
    private static boolean registeredReadsEmbedded;

    private RenderPassShaders() {
    }

    /** Populate on render thread before Vulkan warm workers; reload-generation cache. */
    public static boolean readsEmbedded(Material material) {
        if (readsEmbeddedSources != FlwPrograms.SOURCES) {
            readsEmbeddedSources = FlwPrograms.SOURCES;
            READS_EMBEDDED.clear();
            registeredReadsEmbeddedGen = -1;
        }
        MaterialShaders shaders = material.shaders();
        return readsEmbedded(material.light().source()) || readsEmbedded(shaders.vertexSource())
                || readsEmbedded(shaders.fragmentSource()) || registeredReadsEmbedded();
    }

    private static boolean registeredReadsEmbedded() {
        List<Identifier> cutouts = MaterialShaderIndices.cutoutSources().all();
        List<Identifier> fogs = MaterialShaderIndices.fogSources().all();
        long gen = ((long) cutouts.size() << 42) | ((long) fogs.size() << 21) | InstanceTypeIds.count();
        if (gen != registeredReadsEmbeddedGen) {
            registeredReadsEmbeddedGen = gen;
            registeredReadsEmbedded = cutouts.stream().anyMatch(RenderPassShaders::readsEmbedded)
                    || fogs.stream().anyMatch(RenderPassShaders::readsEmbedded)
                    || InstanceTypeIds.snapshot().types().stream().anyMatch(type -> readsEmbedded(type.vertexShader()));
        }
        return registeredReadsEmbedded;
    }

    private static boolean readsEmbedded(Identifier source) {
        return READS_EMBEDDED.computeIfAbsent(source, id -> readsEmbedded(FlwPrograms.SOURCES.get(id)));
    }

    private static boolean readsEmbedded(SourceComponent component) {
        if (EMBEDDED_TOKEN.matcher(component.source()).find()) {
            return true;
        }
        for (SourceComponent included : component.included()) {
            if (readsEmbedded(included)) {
                return true;
            }
        }
        return false;
    }

    private static void fragmentImports(Compilation ctx) {
        ctx.mojImport("minecraft:fog.glsl");
        ctx.mojImport("minecraft:dynamictransforms.glsl");
        ctx.mojImport("minecraft:light.glsl");
        ctx.mojImport("minecraft:globals.glsl");
    }

    public static void mlabProducerDefines(Compilation ctx, OitInsertMode mode) {
        if (mode.needsInterlock()) {
            ctx.requireExtension("GL_ARB_fragment_shader_interlock");
        }
        ctx.define("_FLW_OIT_INSERT");
        ctx.define("_FLW_MLAB_PRODUCER");
        ctx.define("_FLW_OIT_TRANSMIT_EPS", "0.05");
        ctx.define(mode.define);
    }

    private static void mlabResolveDefines(Compilation ctx, OitInsertMode mode) {
        ctx.define(mode.define);
        ctx.define("_FLW_MLAB_MAX", "32");
        if (mode == OitInsertMode.ABUFFER) {
            ctx.define("_FLW_MLAB_WINDOW", "16u");
        }
    }

    private static void coefficientDefines(Compilation ctx) {
        if (OitConfig.coefficientArray()) {
            ctx.define("_FLW_COEFF_ARRAY");
        }
    }

    private static String assemble(String dumpName, Consumer<Compilation> preamble, List<SourceComponent> roots) {
        String flattened = ShaderAssembly.assembleFlattened(preamble, roots);
        dump(flattened, dumpName);
        return flattened;
    }

    public static Consumer<Compilation> maybeBindlessGl() {
        return GlCompat.SUPPORTS_BINDLESS_TEXTURES ? BINDLESS_GL : ShaderAssembly.NO_EXTRA;
    }

    public static String assembleIndirectVertex(InstanceType<?> type, MaterialShaders materialShaders, boolean debug,
                                                Consumer<Compilation> extra) {
        return assembleVertex(type, INDIRECT_PREAMBLE.andThen(debugVertexExtra(debug)).andThen(extra),
                new SsboInstanceComponent(type), INDIRECT_MAIN, "indirect" + debugVertexSuffix(debug), false,
                materialShaders);
    }

    public static String assembleUberIndirectVertex(MaterialShaders materialShaders, boolean debug,
                                                    Consumer<Compilation> extra) {
        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(HEADER));
        roots.add(FlwPrograms.SOURCES.get(DRAW_COMMAND));
        roots.add(FlwPrograms.SOURCES.get(MATRICES));
        roots.add(new UberInstanceComponent(InstanceTypeIds.snapshot().types(), FlwPrograms.SOURCES));
        roots.add(FlwPrograms.SOURCES.get(materialShaders.vertexSource()));
        roots.add(FlwPrograms.SOURCES.get(INDIRECT_MAIN));

        return assemble("indirect_uber_" + ResourceUtil.toDebugFileNameNoExtension(
                        materialShaders.vertexSource()) + debugVertexSuffix(debug) + ".vsh",
                INDIRECT_PREAMBLE.andThen(ctx -> ctx.define("_FLW_UBER_VERTEX")).andThen(debugVertexExtra(debug))
                                 .andThen(extra), roots);
    }

    public static String assembleInstancingVertex(InstanceType<?> type, MaterialShaders materialShaders,
                                                  boolean debug) {
        return assembleVertex(type, INSTANCING_VERTEX_PREAMBLE.andThen(instancingDebugExtra(debug)),
                new BufferTextureInstanceComponent(type), INSTANCING_MAIN, "instancing" + debugVertexSuffix(debug),
                false, materialShaders);
    }

    public static String assembleIndirectCrumblingVertex(InstanceType<?> type, boolean debug) {
        return assembleVertex(type, INDIRECT_CRUMBLING_PREAMBLE.andThen(debugVertexExtra(debug)),
                new SsboInstanceComponent(type), INDIRECT_MAIN, "indirect_crumbling" + debugVertexSuffix(debug), false,
                StandardMaterialShaders.DEFAULT);
    }

    public static String assembleInstancingCrumblingVertex(InstanceType<?> type, boolean debug) {
        return assembleVertex(type, INSTANCING_CRUMBLING_PREAMBLE.andThen(instancingDebugExtra(debug)),
                new BufferTextureInstanceComponent(type), INSTANCING_MAIN,
                "instancing_crumbling" + debugVertexSuffix(debug), false, StandardMaterialShaders.DEFAULT);
    }

    public static String assembleIndirectEmbeddedVertex(InstanceType<?> type, MaterialShaders materialShaders,
                                                        boolean debug) {
        return assembleIndirectEmbeddedVertex(type, materialShaders, debug, ShaderAssembly.NO_EXTRA);
    }

    public static String assembleIndirectEmbeddedVertex(InstanceType<?> type, MaterialShaders materialShaders,
                                                        boolean debug, Consumer<Compilation> extra) {
        return assembleVertex(type, INDIRECT_EMBEDDED_PREAMBLE.andThen(debugVertexExtra(debug)).andThen(extra),
                new SsboInstanceComponent(type), INDIRECT_MAIN, "indirect_embedded" + debugVertexSuffix(debug), true,
                materialShaders);
    }

    public static String assembleInstancingEmbeddedVertex(InstanceType<?> type, MaterialShaders materialShaders,
                                                          boolean debug) {
        return assembleVertex(type, INSTANCING_EMBEDDED_PREAMBLE.andThen(instancingDebugExtra(debug)),
                new BufferTextureInstanceComponent(type), INSTANCING_MAIN,
                "instancing_embedded" + debugVertexSuffix(debug), false, materialShaders);
    }

    public static String crumblingFragment(boolean indirect, LightSmoothness smoothness, DebugMode debug) {
        List<SourceComponent> roots = new ArrayList<>(lightingRoots(LightShaders.SMOOTH_WHEN_EMBEDDED, indirect));
        roots.add(FlwPrograms.SOURCES.get(StandardMaterialShaders.DEFAULT.fragmentSource()));
        roots.add(FlwPrograms.SOURCES.get(FRAGMENT));

        return assemble(
                "crumbling_fragment" + (indirect ? "_indirect" : "_instancing") + "_" + smoothness.getSerializedName()
                        + debugSuffix(debug) + ".fsh",
                ctx -> {
                    fragmentImports(ctx);
                    ctx.define("_FLW_CRUMBLING");
                    lightingDefines(ctx, indirect, smoothness);
                    debugDefine(ctx, debug);
                }, roots);
    }

    public static String fragment(LightShader light, boolean indirect, MaterialShaders materialShaders,
                                  LightSmoothness smoothness, CutoutShader cutout, FogShader fog, DebugMode debug,
                                  Consumer<Compilation> extra) {
        List<SourceComponent> roots = new ArrayList<>(lightingRoots(light, indirect));
        roots.add(FlwPrograms.SOURCES.get(materialShaders.fragmentSource()));
        boolean useDiscard = cutout != CutoutShaders.OFF;
        if (useDiscard) {
            roots.add(new RawSource("flw_frag_clip_varyings", FRAG_CLIP_VARYINGS));
            roots.add(FlwPrograms.SOURCES.get(cutout.source()));
        }
        roots.add(FlwPrograms.SOURCES.get(fog.source()));
        roots.add(FlwPrograms.SOURCES.get(FRAGMENT));

        return assemble("fragment_" + ResourceUtil.toDebugFileNameNoExtension(light.source()) + "_"
                        + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                        + "_" + ResourceUtil.toDebugFileNameNoExtension(cutout.source())
                        + "_" + ResourceUtil.toDebugFileNameNoExtension(fog.source())
                        + (indirect ? "_indirect" : "_instancing") + "_" + smoothness.getSerializedName()
                        + debugSuffix(debug) + ".fsh",
                ctx -> {
                    fragmentImports(ctx);
                    lightingDefines(ctx, indirect, smoothness);
                    debugDefine(ctx, debug);
                    if (useDiscard) {
                        ctx.define("_FLW_USE_DISCARD");
                    }
                    extra.accept(ctx);
                }, roots);
    }

    private static String debugSuffix(DebugMode debug) {
        return debug == DebugMode.OFF ? "" : "_debug_" + debug.getSerializedName();
    }

    private static void debugDefine(Compilation ctx, DebugMode debug) {
        if (debug != DebugMode.OFF) {
            ctx.define("_FLW_DEBUG", String.valueOf(debug.ordinal()));
        }
    }

    public static Consumer<Compilation> debugExtra(DebugMode debug) {
        return ctx -> debugDefine(ctx, debug);
    }

    private static Consumer<Compilation> debugVertexExtra(boolean debug) {
        return debug ? ctx -> ctx.define("_FLW_DEBUG") : ShaderAssembly.NO_EXTRA;
    }

    private static Consumer<Compilation> instancingDebugExtra(boolean debug) {
        return ctx -> {
            if (debug) {
                if (!GlCompat.USE_INSTANCING_SELECTOR && ShaderAssembly.glslVersion().version < 460) {
                    ctx.requireExtension("GL_ARB_shader_draw_parameters");
                }
                ctx.define("_FLW_DEBUG");
            }
        };
    }

    private static String debugVertexSuffix(boolean debug) {
        return debug ? "_debug" : "";
    }

    public static String uberFragment(LightShader light, MaterialShaders materialShaders, LightSmoothness smoothness,
                                      DebugMode debug, Consumer<Compilation> extra) {
        List<SourceComponent> roots = new ArrayList<>(lightingRoots(light, true));
        roots.add(FlwPrograms.SOURCES.get(materialShaders.fragmentSource()));
        roots.add(new RawSource("flw_frag_clip_varyings", FRAG_CLIP_VARYINGS));
        roots.add(new UberMaterialShaderComponent(FlwPrograms.SOURCES));
        roots.add(FlwPrograms.SOURCES.get(FRAGMENT));

        return assemble("fragment_uber_" + ResourceUtil.toDebugFileNameNoExtension(light.source()) + "_"
                        + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                        + "_" + smoothness.getSerializedName() + debugSuffix(debug) + ".fsh",
                ctx -> {
                    fragmentImports(ctx);
                    lightingDefines(ctx, true, smoothness);
                    debugDefine(ctx, debug);
                    ctx.define("_FLW_UBER_FRAGMENT");
                    extra.accept(ctx);
                }, roots);
    }

    public static String assembleUberOitFragment(OitMode mode, LightShader light, MaterialShaders materialShaders,
                                                 LightSmoothness smoothness, DebugMode debug,
                                                 Consumer<Compilation> extra) {
        boolean depthRange = mode == OitMode.DEPTH_RANGE;

        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(WAVELET));
        if (!depthRange) {
            roots.addAll(lightingRoots(light, true));
            roots.add(FlwPrograms.SOURCES.get(materialShaders.fragmentSource()));
            roots.add(new RawSource("flw_frag_clip_varyings", FRAG_CLIP_VARYINGS));
            roots.add(new UberMaterialShaderComponent(FlwPrograms.SOURCES));
        }
        roots.add(FlwPrograms.SOURCES.get(OIT_FRAGMENT));

        return assemble("oit_uber" + mode.name + "_" + ResourceUtil.toDebugFileNameNoExtension(light.source()) + "_"
                        + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                        + "_" + smoothness.getSerializedName() + debugSuffix(debug) + ".fsh",
                ctx -> {
                    ctx.define("_FLW_OIT");
                    coefficientDefines(ctx);
                    if (!mode.define.isEmpty()) {
                        ctx.define(mode.define);
                    }
                    if (!depthRange) {
                        fragmentImports(ctx);
                        lightingDefines(ctx, true, smoothness);
                        ctx.define("_FLW_UBER_FRAGMENT");
                        debugDefine(ctx, debug);
                    }
                    extra.accept(ctx);
                }, roots);
    }

    public static String assembleUberMlabFragment(OitInsertMode oitMode, LightShader light,
                                                  MaterialShaders materialShaders, LightSmoothness smoothness,
                                                  DebugMode debug, Consumer<Compilation> extra) {
        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(MLAB));
        roots.addAll(lightingRoots(light, true));
        roots.add(FlwPrograms.SOURCES.get(materialShaders.fragmentSource()));
        roots.add(new RawSource("flw_frag_clip_varyings", FRAG_CLIP_VARYINGS));
        roots.add(new UberMaterialShaderComponent(FlwPrograms.SOURCES));
        roots.add(FlwPrograms.SOURCES.get(OIT_FRAGMENT));

        return assemble("mlab_uber_" + oitMode.name().toLowerCase(Locale.ROOT) + "_"
                        + ResourceUtil.toDebugFileNameNoExtension(light.source()) + "_"
                        + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                        + "_" + smoothness.getSerializedName() + debugSuffix(debug) + ".fsh",
                ctx -> {
                    mlabProducerDefines(ctx, oitMode);
                    ctx.define("_FLW_OIT");
                    fragmentImports(ctx);
                    lightingDefines(ctx, true, smoothness);
                    ctx.define("_FLW_UBER_FRAGMENT");
                    debugDefine(ctx, debug);
                    extra.accept(ctx);
                }, roots);
    }

    public static String assembleChunkMlabFragment(OitInsertMode oitMode, boolean linear) {
        return assembleChunkMlabFragment(oitMode, linear, ShaderAssembly.NO_EXTRA);
    }

    public static String assembleChunkMlabFragment(OitInsertMode oitMode, boolean linear, Consumer<Compilation> extra) {
        List<SourceComponent> roots = List.of(
                FlwPrograms.SOURCES.get(MLAB),
                FlwPrograms.SOURCES.get(TEXEL_FILTER),
                FlwPrograms.SOURCES.get(CHUNK_OIT_FRAGMENT));

        return assemble(
                "chunk_mlab_" + oitMode.name().toLowerCase(Locale.ROOT) + (linear ? "_linear" : "") + ".fsh",
                ctx -> {
                    mlabProducerDefines(ctx, oitMode);
                    ctx.define("_FLW_OIT");
                    ctx.mojImport("minecraft:globals.glsl");
                    if (linear) {
                        ctx.define(TerrainAtlasFilter.LINEAR_DEFINE);
                    }
                    extra.accept(ctx);
                }, roots);
    }

    public static String assembleBerMlabFragment(BerFamily family, OitInsertMode oitMode) {
        List<SourceComponent> roots = List.of(
                FlwPrograms.SOURCES.get(MLAB),
                FlwPrograms.SOURCES.get(berFragmentSource(family)));
        return assemble("ber_mlab" + family.suffix + "_" + oitMode.name().toLowerCase(Locale.ROOT) + ".fsh",
                ctx -> {
                    mlabProducerDefines(ctx, oitMode);
                    ctx.define("_FLW_OIT");
                    berFamilyDefines(family).accept(ctx);
                }, roots);
    }

    public static String assembleWeatherMlabFragment(OitInsertMode oitMode) {
        List<SourceComponent> roots = List.of(
                FlwPrograms.SOURCES.get(MLAB),
                FlwPrograms.SOURCES.get(WEATHER_OIT_FRAGMENT));
        return assemble("weather_mlab_" + oitMode.name().toLowerCase(Locale.ROOT) + ".fsh",
                ctx -> {
                    mlabProducerDefines(ctx, oitMode);
                    ctx.define("_FLW_OIT");
                }, roots);
    }

    public static String assembleMlabResolve(OitInsertMode oitMode) {
        List<SourceComponent> roots = List.of(
                FlwPrograms.SOURCES.get(MLAB),
                FlwPrograms.SOURCES.get(MLAB_RESOLVE));
        return assemble("mlab_resolve_" + oitMode.name().toLowerCase(Locale.ROOT) + ".fsh",
                ctx -> mlabResolveDefines(ctx, oitMode), roots);
    }

    private static List<SourceComponent> lightingRoots(LightShader light, boolean indirect) {
        return List.of(
                FlwPrograms.SOURCES.get(MATERIAL),
                new RawSource("flw_frag_lighting_prelude", FRAG_LIGHTING_PRELUDE),
                FlwPrograms.SOURCES.get(RENDER_ORIGIN),
                FlwPrograms.SOURCES.get(PACKED_MATERIAL),
                FlwPrograms.SOURCES.get(DIFFUSE),
                FlwPrograms.SOURCES.get(COLORIZER),
                new RawSource("flw_diffuse_factor", FRAG_DIFFUSE_FACTOR),
                FlwPrograms.SOURCES.get(indirect ? INDIRECT_LIGHT : INSTANCING_LIGHT),
                FlwPrograms.SOURCES.get(light.source()));
    }

    private static void lightingDefines(Compilation ctx, boolean indirect, LightSmoothness smoothness) {
        smoothness.appendDefines(ctx);
        ctx.define("flw_renderOrigin", "_flw_renderOrigin.xyz");
        ctx.define("flw_constantAmbientLight", "_flw_constantAmbientLight");
        ctx.define("flw_light0Direction", "Light0_Direction");
        ctx.define("flw_light1Direction", "Light1_Direction");
        if (indirect) {
            ctx.define("_FLW_LIGHT_LUT_BUFFER_BINDING", "5");
            ctx.define("_FLW_LIGHT_SECTIONS_BUFFER_BINDING", "6");
        }
    }

    public static String assembleOitFragment(OitMode mode, LightShader light, boolean indirect,
                                             MaterialShaders materialShaders, LightSmoothness smoothness,
                                             CutoutShader cutout, FogShader fog, DebugMode debug) {
        return assembleOitFragment(mode, light, indirect, materialShaders, smoothness, cutout, fog, debug,
                ShaderAssembly.NO_EXTRA);
    }

    public static String assembleOitFragment(OitMode mode, LightShader light, boolean indirect,
                                             MaterialShaders materialShaders, LightSmoothness smoothness,
                                             CutoutShader cutout, FogShader fog, DebugMode debug,
                                             Consumer<Compilation> extra) {
        boolean depthRange = mode == OitMode.DEPTH_RANGE;
        boolean useDiscard = !depthRange && cutout != CutoutShaders.OFF;

        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(WAVELET));
        if (!depthRange) {
            roots.addAll(lightingRoots(light, indirect));
            roots.add(FlwPrograms.SOURCES.get(materialShaders.fragmentSource()));
            if (useDiscard) {
                roots.add(new RawSource("flw_frag_clip_varyings", FRAG_CLIP_VARYINGS));
                roots.add(FlwPrograms.SOURCES.get(cutout.source()));
            }
            roots.add(FlwPrograms.SOURCES.get(fog.source()));
        }
        roots.add(FlwPrograms.SOURCES.get(OIT_FRAGMENT));

        return assemble("oit" + mode.name + "_" + ResourceUtil.toDebugFileNameNoExtension(light.source()) + "_"
                        + ResourceUtil.toDebugFileNameNoExtension(materialShaders.fragmentSource())
                        + "_" + ResourceUtil.toDebugFileNameNoExtension(cutout.source())
                        + "_" + ResourceUtil.toDebugFileNameNoExtension(fog.source())
                        + (indirect ? "_indirect" : "_instancing") + "_" + smoothness.getSerializedName()
                        + debugSuffix(debug) + ".fsh",
                ctx -> {
                    ctx.define("_FLW_OIT");
                    coefficientDefines(ctx);
                    if (!mode.define.isEmpty()) {
                        ctx.define(mode.define);
                    }
                    if (!depthRange) {
                        fragmentImports(ctx);
                        lightingDefines(ctx, indirect, smoothness);
                        debugDefine(ctx, debug);
                        if (useDiscard) {
                            ctx.define("_FLW_USE_DISCARD");
                        }
                    }
                    extra.accept(ctx);
                }, roots);
    }

    public static String assembleChunkOitVertex() {
        return assemble("chunk_oit.vsh", INSTANCING_PREAMBLE, List.of(FlwPrograms.SOURCES.get(CHUNK_OIT_VERTEX)));
    }

    public static String assembleSodiumChunkOitVertex(boolean mdi, boolean fade) {
        return assembleSodiumChunkOitVertex(mdi, fade, ShaderAssembly.NO_EXTRA);
    }

    public static String assembleSodiumChunkOitVertex(boolean mdi, boolean fade, Consumer<Compilation> extra) {
        return assemble("chunk_oit_sodium" + (mdi ? (fade ? "_mdi_fade" : "_mdi") : "") + ".vsh",
                ctx -> {
                    if (mdi) {
                        ctx.define("_FLW_TRANSLUCENT_MDI");
                    }
                    if (fade) {
                        ctx.define("_FLW_TRANSLUCENT_FADE");
                    }
                    TerrainVertexFormat.appendDefines(ctx);
                    extra.accept(ctx);
                }, List.of(FlwPrograms.SOURCES.get(CHUNK_OIT_SODIUM_VERTEX)));
    }

    public static String assembleChunkOitFragment(OitMode mode, boolean linear) {
        return assembleChunkOitFragment(mode, linear, ShaderAssembly.NO_EXTRA);
    }

    public static String assembleChunkOitFragment(OitMode mode, boolean linear, Consumer<Compilation> extra) {
        List<SourceComponent> roots = List.of(
                FlwPrograms.SOURCES.get(WAVELET),
                FlwPrograms.SOURCES.get(TEXEL_FILTER),
                FlwPrograms.SOURCES.get(CHUNK_OIT_FRAGMENT));

        return assemble("chunk_oit" + mode.name + (linear ? "_linear" : "") + ".fsh",
                ctx -> {
                    ctx.define("_FLW_OIT");
                    ctx.mojImport("minecraft:globals.glsl");
                    coefficientDefines(ctx);
                    if (linear) {
                        ctx.define(TerrainAtlasFilter.LINEAR_DEFINE);
                    }
                    if (!mode.define.isEmpty()) {
                        ctx.define(mode.define);
                    }
                    extra.accept(ctx);
                }, roots);
    }

    private static Identifier berVertexSource(BerFamily family) {
        return switch (family) {
            case ENTITY, ITEM, ENTITY_EMISSIVE -> BER_OIT_VERTEX;
            case MOVING_BLOCK -> BLOCK_OIT_VERTEX;
            case BEAM -> BEAM_OIT_VERTEX;
        };
    }

    private static Identifier berFragmentSource(BerFamily family) {
        return switch (family) {
            case ENTITY, ITEM, ENTITY_EMISSIVE -> BER_OIT_FRAGMENT;
            case MOVING_BLOCK -> BLOCK_OIT_FRAGMENT;
            case BEAM -> BEAM_OIT_FRAGMENT;
        };
    }

    private static Consumer<Compilation> berFamilyDefines(BerFamily family) {
        return switch (family) {
            case ENTITY -> ctx -> ctx.define("_FLW_BER_PER_FACE");
            case ITEM, MOVING_BLOCK, BEAM -> ShaderAssembly.NO_EXTRA;
            case ENTITY_EMISSIVE -> ctx -> {
                ctx.define("_FLW_BER_PER_FACE");
                ctx.define("_FLW_BER_EMISSIVE");
            };
        };
    }

    public static String assembleBerOitVertex(BerFamily family) {
        return assemble("ber_oit" + family.suffix + ".vsh", INSTANCING_PREAMBLE.andThen(berFamilyDefines(family)),
                List.of(FlwPrograms.SOURCES.get(berVertexSource(family))));
    }

    public static String assembleBerOitFragment(BerFamily family, OitMode mode) {
        return assembleBerOitFragment(family, mode, ShaderAssembly.NO_EXTRA);
    }

    public static String assembleBerOitFragment(BerFamily family, OitMode mode, Consumer<Compilation> extra) {
        return assembleWaveletOitFragment(berFragmentSource(family), "ber_oit" + family.suffix, mode,
                berFamilyDefines(family).andThen(extra));
    }

    public static String assembleLayerOitFragment(OitMode mode) {
        return assembleLayerOitFragment(mode, ShaderAssembly.NO_EXTRA);
    }

    public static String assembleLayerOitFragment(OitMode mode, Consumer<Compilation> extra) {
        return assembleWaveletOitFragment(LAYER_OIT_FRAGMENT, "layer_oit", mode, extra);
    }

    public static String assembleWeatherOitVertex() {
        return assemble("weather_oit.vsh", INSTANCING_PREAMBLE, List.of(FlwPrograms.SOURCES.get(WEATHER_OIT_VERTEX)));
    }

    public static String assembleWeatherOitFragment(OitMode mode) {
        return assembleWeatherOitFragment(mode, ShaderAssembly.NO_EXTRA);
    }

    public static String assembleWeatherOitFragment(OitMode mode, Consumer<Compilation> extra) {
        return assembleWaveletOitFragment(WEATHER_OIT_FRAGMENT, "weather_oit", mode, extra);
    }

    private static String assembleWaveletOitFragment(Identifier body, String dumpPrefix, OitMode mode,
                                                     Consumer<Compilation> extra) {
        List<SourceComponent> roots = List.of(
                FlwPrograms.SOURCES.get(WAVELET),
                FlwPrograms.SOURCES.get(body));

        return assemble(dumpPrefix + mode.name + ".fsh",
                ctx -> {
                    ctx.define("_FLW_OIT");
                    coefficientDefines(ctx);
                    if (!mode.define.isEmpty()) {
                        ctx.define(mode.define);
                    }
                    extra.accept(ctx);
                }, roots);
    }

    public static String assembleOitComposite(boolean emission) {
        return emission
                ? assembleFullscreenFragment(OIT_COMPOSITE, "oit_composite_emission.fsh", EMISSION_PRODUCER)
                : assembleFullscreenFragment(OIT_COMPOSITE, "oit_composite.fsh", ShaderAssembly.NO_EXTRA);
    }

    public static String assembleOitEmission() {
        return assembleFullscreenFragment(OIT_EMISSION, "oit_emission.fsh", ShaderAssembly.NO_EXTRA);
    }

    public static String assembleOitDepth() {
        return assembleFullscreenFragment(OIT_DEPTH, "oit_depth.fsh", ShaderAssembly.NO_EXTRA);
    }

    public static String assembleMlabNearestDepth() {
        return assembleFullscreenFragment(MLAB_NEAREST_DEPTH, "mlab_nearest_depth.fsh", ShaderAssembly.NO_EXTRA);
    }

    public static String fullscreenVertex() {
        return assemble("fullscreen.vsh", INSTANCING_PREAMBLE, List.of(FlwPrograms.SOURCES.get(FULLSCREEN_VERT)));
    }

    private static String assembleFullscreenFragment(Identifier location, String dumpName,
                                                     Consumer<Compilation> extra) {
        return assemble(dumpName, INSTANCING_PREAMBLE.andThen(RenderPassShaders::coefficientDefines).andThen(extra),
                List.of(
                        FlwPrograms.SOURCES.get(WAVELET),
                        FlwPrograms.SOURCES.get(location)));
    }

    private static String assembleVertex(InstanceType<?> type, Consumer<Compilation> preamble,
                                         SourceComponent assembler,
                                         Identifier mainLocation, String backend, boolean indirectEmbedded,
                                         MaterialShaders materialShaders) {
        List<SourceComponent> roots = new ArrayList<>();
        roots.add(FlwPrograms.SOURCES.get(HEADER));
        if (backend.startsWith("indirect")) {
            roots.add(FlwPrograms.SOURCES.get(DRAW_COMMAND));
        }
        if (indirectEmbedded) {
            roots.add(FlwPrograms.SOURCES.get(MATRICES));
        }
        roots.add(new InstanceStructComponent(type));
        roots.add(FlwPrograms.SOURCES.get(type.vertexShader()));
        roots.add(assembler);
        roots.add(FlwPrograms.SOURCES.get(materialShaders.vertexSource()));
        roots.add(FlwPrograms.SOURCES.get(mainLocation));

        return assemble(backend + "_" + ResourceUtil.toDebugFileNameNoExtension(type.vertexShader())
                        + "_" + ResourceUtil.toDebugFileNameNoExtension(materialShaders.vertexSource()) + ".vsh", preamble,
                roots);
    }

    private static void dump(String source, String fileName) {
        if (!Compilation.DUMP_SHADER_SOURCE) {
            return;
        }
        synchronized (RenderPassShaders.class) {
            File file = new File(new File(Minecraft.getInstance().gameDirectory, "flywheel_sources/renderpass"),
                    fileName);
            file.getParentFile()
                .mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(source);
            } catch (Exception e) {
                FlwPrograms.LOGGER.error("Could not dump RenderPass source {}", fileName, e);
            }
        }
    }
}
