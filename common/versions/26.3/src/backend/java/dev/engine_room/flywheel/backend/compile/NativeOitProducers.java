package dev.engine_room.flywheel.backend.compile;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Native Improved Transparency producer variants and source assembly shared by the GL and Vulkan adapters. */
final class NativeOitProducers {
    static final String DEPTH_BOUNDS = "OIT_DEPTH_BOUNDS";
    static final String TRANSMITTANCE = "OIT_TRANSMITTANCE";
    static final String ACCUMULATE = "OIT_ACCUMULATE";
    static final String ADDITIVE = "OIT_ADDITIVE";
    static final Set<String> NATIVE_READS = Set.of("DepthBoundsSampler", "Coeff0", "Coeff1", "Coeff2", "Coeff3");
    private static final Identifier ADAPTER = ResourceUtil.rl("internal/native_oit.glsl");
    private static final Identifier WRAPPER = ResourceUtil.rl("internal/native_oit_main.glsl");
    private static final Identifier WAVELET = ResourceUtil.rl("internal/wavelet.glsl");
    private static final Identifier PRODUCER = ResourceUtil.rl("internal/oit_producer.glsl");
    private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version[^\\r\\n]*");
    private static final Map<ShaderKey, String> RAW_SHADERS = new ConcurrentHashMap<>();

    private NativeOitProducers() {
    }

    static boolean defined(ShaderDefines defines, String name) {
        return defines.flags().contains(name) || defines.values().containsKey(name);
    }

    /** Null: not a native OIT producer. */
    static @Nullable OitMode stage(ShaderDefines defines) {
        if (defined(defines, DEPTH_BOUNDS)) return OitMode.DEPTH_RANGE;
        if (defined(defines, TRANSMITTANCE)) return OitMode.GENERATE_COEFFICIENTS;
        if (defined(defines, ACCUMULATE)) return OitMode.EVALUATE;
        return null;
    }

    static boolean isProducer(ShaderDefines defines) {
        return stage(defines) != null;
    }

    static Identifier location(String family, Variant variant, RenderPipeline base) {
        return ResourceUtil.rl("pipeline/" + family + "/" + variant.name().toLowerCase(Locale.ROOT) + "/"
                + base.getLocation().getNamespace() + "/" + base.getLocation().getPath());
    }

    /** {@code projection}: append {@code Projection} when no kept group declares it. */
    static List<BindGroupLayout> layouts(RenderPipeline base, Predicate<String> dropped, boolean projection) {
        List<BindGroupLayout> result = new ArrayList<>();
        boolean declared = false;
        for (var layout : base.getBindGroupLayouts()) {
            var uniforms = layout.uniforms().stream().filter(uniform -> !dropped.test(uniform.name())).toList();
            if (uniforms.isEmpty()) continue;
            declared |= uniforms.stream().anyMatch(uniform -> uniform.name().equals("Projection"));
            result.add(new BindGroupLayout(uniforms));
        }
        if (projection && !declared) result.add(BindGroupLayouts.PROJECTION);
        return result;
    }

    /** {@link #readShader}, cached until {@link #reset}. */
    static String rawShader(Identifier id, ShaderType type) {
        return RAW_SHADERS.computeIfAbsent(new ShaderKey(id, type), key -> readShader(key.id, key.type));
    }

    /** Native source with its {@code #version} line removed; assembly supplies the engine's version. */
    static String readShader(Identifier id, ShaderType type) {
        try {
            return VERSION.matcher(Minecraft.getInstance().getResourceManager()
                    .getResource(type.idConverter().idToFile(id)).orElseThrow().readAllAsString()).replaceFirst("");
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read native shader " + id, failure);
        }
    }

    /** Resource invalidation. */
    static void reset() {
        RAW_SHADERS.clear();
    }

    /** Producer fragment: engine prologue, native source, then the stage wrapper. */
    static void assembleFragment(Compilation compilation, Variant variant, boolean localRead, boolean coefficientArray,
                                 Identifier id) {
        compilation.define("MINECRAFT_OIT_GLSL");
        compilation.define("main", "_flw_nativeMain");
        if (localRead) compilation.define("_FLW_OIT_LOCAL_READ");
        if (variant.insert != null) {
            RenderPassShaders.mlabProducerDefines(compilation, variant.insert);
            ShaderAssembly.appendComponents(compilation, List.of(FlwPrograms.SOURCES.get(RenderPassShaders.MLAB)));
        } else {
            compilation.define(variant.mode.define);
            if (variant == Variant.EMISSION) compilation.define("_FLW_OIT_EMISSION");
            if (coefficientArray) compilation.define("_FLW_COEFF_ARRAY");
            ShaderAssembly.appendComponents(compilation, List.of(FlwPrograms.SOURCES.get(WAVELET)));
            if (variant.mode != OitMode.EVALUATE) {
                ShaderAssembly.appendComponents(compilation, List.of(FlwPrograms.SOURCES.get(PRODUCER)));
            }
        }
        ShaderAssembly.appendComponents(compilation, List.of(FlwPrograms.SOURCES.get(ADAPTER)));
        compilation.appendComponent(new ShaderAssembly.RawSource(id.toString(), rawShader(id, ShaderType.FRAGMENT)));
        ShaderAssembly.appendComponents(compilation, List.of(FlwPrograms.SOURCES.get(WRAPPER)));
    }

    private record ShaderKey(Identifier id, ShaderType type) {
    }

    /** {@code mode}: engine stage, {@code OFF} for insert; {@code nativeStage}: required native producer stage. */
    enum Variant {
        ORIGINAL(OitMode.OFF, null, null),
        DEPTH(OitMode.DEPTH_RANGE, OitMode.DEPTH_RANGE, null),
        COEFFICIENTS(OitMode.GENERATE_COEFFICIENTS, OitMode.GENERATE_COEFFICIENTS, null),
        ACCUMULATE(OitMode.EVALUATE, OitMode.EVALUATE, null),
        EMISSION(OitMode.EVALUATE, OitMode.EVALUATE, null),
        KBUFFER(OitMode.OFF, OitMode.EVALUATE, OitInsertMode.KBUFFER),
        MLAB(OitMode.OFF, OitMode.EVALUATE, OitInsertMode.MLAB),
        ABUFFER(OitMode.OFF, OitMode.EVALUATE, OitInsertMode.ABUFFER);

        final OitMode mode;
        final @Nullable OitMode nativeStage;
        final @Nullable OitInsertMode insert;

        Variant(OitMode mode, @Nullable OitMode nativeStage, @Nullable OitInsertMode insert) {
            this.mode = mode;
            this.nativeStage = nativeStage;
            this.insert = insert;
        }

        static Variant wavelet(OitMode mode, boolean emission) {
            if (emission && mode != OitMode.EVALUATE) {
                throw new IllegalArgumentException("Native emission requires the evaluate stage");
            }
            return switch (mode) {
                case DEPTH_RANGE -> DEPTH;
                case GENERATE_COEFFICIENTS -> COEFFICIENTS;
                case EVALUATE -> emission ? EMISSION : ACCUMULATE;
                case OFF -> throw new IllegalArgumentException("Native OIT replay requires a producer stage");
            };
        }

        static Variant insert(OitInsertMode mode) {
            return switch (mode) {
                case KBUFFER -> KBUFFER;
                case MLAB -> MLAB;
                case ABUFFER -> ABUFFER;
            };
        }

        /** Throws unless {@code pipeline} is a producer of this variant's native stage. */
        void requireStage(RenderPipeline pipeline) {
            if (stage(pipeline.getShaderDefines()) != nativeStage) {
                throw new IllegalStateException("Native OIT pipeline " + pipeline
                        + " belongs to another producer stage");
            }
        }
    }
}
