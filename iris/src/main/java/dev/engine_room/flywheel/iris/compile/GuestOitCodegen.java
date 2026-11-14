package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.iris.IrisRuntime;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;

import java.util.Map;

final class GuestOitCodegen {
    static final Identifier LIBRARY = ResourceUtil.rl("iris/guest_oit.glsl");

    static final String COMPOSITE_VERTEX = GuestShaders.versionPreamble() + """
            void main() {
                vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private GuestOitCodegen() {
    }

    static int layers(int rank) {
        return (1 << (rank + 1)) / 4;
    }

    private static void flatCoefficients(StringBuilder out, int[] ranks, boolean sampled, String pixel) {
        if (!sampled) {
            out.append("vec4 _flw_oitReadLayer(int coefficientSet, int layer) { return vec4(0.0); }\n");
            return;
        }
        for (int set = 0; set < ranks.length; set++) {
            out.append("const int _flw_coefficients").append(set).append(" = ").append(set).append(";\n");
            for (int layer = 0; layer < layers(ranks[set]); layer++) {
                out.append("uniform sampler2D _flw_coefficients").append(set).append('_').append(layer).append(";\n");
            }
        }
        out.append("vec4 _flw_oitReadLayer(int coefficientSet, int layer) {\n");
        for (int set = 0; set < ranks.length; set++) {
            out.append("    if (coefficientSet == ").append(set).append(") {\n");
            for (int layer = 0; layer < layers(ranks[set]); layer++) {
                out.append("        if (layer == ").append(layer).append(") return texelFetch(_flw_coefficients")
                   .append(set).append('_').append(layer).append(", ").append(pixel).append(", 0);\n");
            }
            out.append("    }\n");
        }
        out.append("    return vec4(0.0);\n}\n");
    }

    /**
     * Iris's generic shadow pass is forward; a native-depth pack's is reversed like the main pass.
     */
    private static String deviceDepth(boolean shadow) {
        return shadow && !NativeDepth.active() ? "(1.0 - gl_FragCoord.z)" : "gl_FragCoord.z";
    }

    static String producer(GuestShaders.OitSpec spec, Map<Integer, String> outputs) {
        StringBuilder out = new StringBuilder();
        if (IrisRuntime.EXPERIMENTAL) flatCoefficients(out, spec.oit().ranks(), spec.pass() == OitPass.EVALUATE,
                "ivec2(gl_FragCoord.xy)");
        switch (spec.pass()) {
            case DEPTH_RANGE -> out.append("layout(location = 0) out vec4 _flw_oitDepthRange;\n")
                                   .append("void main() {\n")
                                   .append(evaluationMode(spec))
                                   .append(spec.oit()
                                               .forward() == null ? "" : "    _flw_forwardShadeMaterial = false;\n")
                                   .append("    _flw_irisMain();\n")
                                   .append("    float linearDepth = -_flw_oitViewZ;\n")
                                   .append("    _flw_oitDepthRange = vec4(-linearDepth + 1e-5, linearDepth + 1e-2, ")
                                   .append(deviceDepth(spec.shadow()))
                                   .append(", 0.0);\n}\n");
            case COEFFICIENTS -> coefficients(out, spec, outputs);
            case EVALUATE -> evaluate(out, spec, outputs);
        }
        return out.toString();
    }

    private static String evaluationMode(GuestShaders.OitSpec spec) {
        return spec.oit().forward() != null
                ? "    _flw_forwardEvaluate = " + (spec.pass() == OitPass.EVALUATE) + ";\n" : "";
    }

    private static void material(StringBuilder out, GuestShaders.OitSpec spec) {
        out.append(evaluationMode(spec))
           .append("    vec4 depthRange = texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0);\n");
        if (spec.oit().forward() != null)
            out.append("    _flw_forwardShadeMaterial = gl_FragCoord.z != depthRange.b;\n");
        out.append("    _flw_irisMain();\n");
    }

    private static void normalizedDepth(StringBuilder out) {
        out.append("    float linearDepth = -_flw_oitViewZ;\n")
           .append("    float depth = (linearDepth + depthRange.x) / (depthRange.x + depthRange.y);\n")
           .append("    float adjustment = _flw_oitTentedBlueNoise(depth) * _FLW_GUEST_OIT_NOISE;\n");
    }

    private static void coefficients(StringBuilder out, GuestShaders.OitSpec spec, Map<Integer, String> outputs) {
        int[] ranks = spec.oit()
                          .ranks();
        int attachment = 0;
        for (int rank : ranks) {
            for (int layer = 0; layer < layers(rank); layer++) {
                out.append("layout(location = ")
                   .append(attachment)
                   .append(") out vec4 _flw_oitCoefficients")
                   .append(attachment++)
                   .append(";\n");
            }
        }
        out.append("void main() {\n");
        material(out, spec);
        // Native sampling precedes rejection so surviving fragments retain their derivative inputs.
        if (spec.oit().forward() != null) out.append("    if (!_flw_forwardShadeMaterial) discard;\n");
        normalizedDepth(out);
        attachment = 0;
        for (int set = 0; set < ranks.length; set++) {
            int slot = firstSlot(spec, set);
            String source = outputs.get(slot);
            if (source == null) {
                throw new IllegalStateException(
                        "OIT coefficient set " + set + " reads an output the pack never writes");
            }
            if (spec.oit().accumulate(spec.drawBuffers()[slot]).clampInput())
                source = "clamp(" + source + ", 0.0, 1.0)";
            out.append("    {\n        float transmittance = 1.0 - ")
               .append(source)
               .append(".a;\n        float d = transmittance > 1e-5 ? depth - adjustment : depth;\n")
               .append("        vec4[4] c = vec4[4](vec4(0.0), vec4(0.0), vec4(0.0), vec4(0.0));\n")
               .append("        _flw_oitAddTransmittance(c, transmittance, d, ")
               .append(ranks[set])
               .append(");\n");
            for (int layer = 0; layer < layers(ranks[set]); layer++) {
                out.append("        _flw_oitCoefficients")
                   .append(attachment++)
                   .append(" = c[")
                   .append(layer)
                   .append("];\n");
            }
            out.append("    }\n");
        }
        out.append("}\n");
    }

    private static void evaluate(StringBuilder out, GuestShaders.OitSpec spec, Map<Integer, String> outputs) {
        int[] ranks = spec.oit()
                          .ranks();
        var forward = spec.oit().forward();
        if (forward != null) {
            out.append("layout(r32ui) uniform uimage2D _flw_forwardSurfaceClaim;\n");
            for (int i = 0; i < forward.waterBuffers().length; i++)
                out.append(
                        "layout(rgba32ui) writeonly uniform uimage2D _flw_forwardWater").append(i).append(";\n");
        }
        for (int set = 0; !IrisRuntime.EXPERIMENTAL && set < ranks.length; set++) {
            out.append("uniform sampler2DArray _flw_coefficients")
               .append(set)
               .append(";\n");
        }
        int[] drawBuffers = spec.drawBuffers();
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            out.append("layout(location = ")
               .append(slot)
               .append(") out vec4 _flw_oitAccumulate")
               .append(slot)
               .append(";\n");
        }
        out.append("void main() {\n");
        material(out, spec);
        if (forward != null) {
            // Coplanar nearest fragments share one coherent native surface; their optical order is unspecified.
            out.append("    if (gl_FragCoord.z == depthRange.b && imageAtomicCompSwap(_flw_forwardSurfaceClaim, "
                    + "ivec2(gl_FragCoord.xy), 0u, 1u) == 0u) {\n");
            for (int i = 0; i < forward.waterBuffers().length; i++)
                out.append("        imageStore(_flw_forwardWater")
                   .append(i).append(", ivec2(gl_FragCoord.xy), floatBitsToUint(_flw_forwardNative[")
                   .append(i).append("]));\n");
            // Prior storage-image writes survive discard; the native front surface has no wavelet signal.
            out.append("    }\n    if (!_flw_forwardShadeMaterial) discard;\n");
        }
        normalizedDepth(out);
        out.append("    float frontmost = linearDepth <= -depthRange.x + 2e-5 ? 1.0 : 0.0;\n");
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            String source = outputs.get(slot);
            if (spec.oit().forward() != null && slot == 1) {
                out.append("    _flw_oitAccumulate1 = ").append(source).append(";\n");
                continue;
            }
            int set = coefficientSet(spec, slot);
            if (source != null && spec.oit().accumulate(drawBuffers[slot]).clampInput())
                source = "clamp(" + source + ", 0.0, 1.0)";
            out.append("    {\n        vec4 v = ")
               .append(source == null ? "vec4(0.0)" : source)
               .append(";\n");
            if (set == ContractProperties.Accumulate.FRONTMOST) {
                out.append("        v *= frontmost;\n");
            } else {
                out.append("        float transmittance = 1.0 - v.a;\n")
                   .append("        float d = transmittance > 1e-5 ? depth - adjustment : depth;\n")
                   .append(spec.oit().accumulate(drawBuffers[slot]).premultiplied() ? "" : "        v.rgb *= v.a;\n")
                   .append("        v *= _flw_oitSignalCorrectedTransmittance(_flw_coefficients")
                   .append(set)
                   .append(", d, transmittance, ")
                   .append(ranks[set])
                   .append(");\n");
            }
            out.append("        _flw_oitAccumulate")
               .append(slot)
               .append(" = v;\n    }\n");
        }
        out.append("}\n");
    }

    static String compositeFragment(GuestShaders.OitSpec spec) {
        return GuestShaders.versionPreamble() + compositeBody(spec);
    }

    static String compositeBody(GuestShaders.OitSpec spec) {
        int[] ranks = spec.oit()
                          .ranks();
        int[] drawBuffers = spec.drawBuffers();
        boolean packed = spec.oit().forward() != null && spec.oit().forward().packed();
        boolean colored = spec.oit().forward() != null;
        String pixel = packed ? "(ivec2(gl_FragCoord.xy) - textureSize(_flw_forwardBackground, 0) / 2 - ivec2(1))"
                : "ivec2(gl_FragCoord.xy)";
        StringBuilder out = new StringBuilder(GuestShaders.libraryPreamble());
        var extra = new Compilation();
        if (packed) extra.define("_FLW_GUEST_OIT_PIXEL", pixel);
        out.append(extra.assembledSource());
        if (packed || colored) out.append("uniform sampler2D _flw_forwardBackground;\n");
        out.append(FlwPrograms.SOURCES.get(LIBRARY)
                                      .source())
           .append('\n');
        if (IrisRuntime.EXPERIMENTAL) flatCoefficients(out, ranks, true, pixel);
        for (int set = 0; !IrisRuntime.EXPERIMENTAL && set < ranks.length; set++) {
            out.append("uniform sampler2DArray _flw_coefficients")
               .append(set)
               .append(";\n");
        }
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            out.append("uniform sampler2D _flw_accumulate")
               .append(slot)
               .append(";\n");
            if (colored && slot == 1) continue;
            out.append("layout(location = ")
               .append(slot)
               .append(") out vec4 _flw_frag")
               .append(slot)
               .append(";\n");
        }
        out.append("void main() {\n")
           .append("    ivec2 pixel = ").append(pixel).append(";\n");
        if (packed) out.append(
                "    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, textureSize(_flw_depthRange, 0)))) discard;\n");
        out.append("    vec4 range = texelFetch(_flw_depthRange, pixel, 0);\n")
           .append("    if (range.b <= 0.0) {\n        discard;\n    }\n");
        if (colored) out.append("""
                    vec4 texel0 = texelFetch(_flw_accumulate0, pixel, 0);
                    vec3 logTint = texelFetch(_flw_accumulate1, pixel, 0).rgb;
                    if (all(equal(texel0, vec4(0.0))) && all(equal(logTint, vec3(0.0)))) discard;
                """);
        for (int set = 0; set < ranks.length; set++) {
            out.append("    float total")
               .append(set)
               .append(" = _flw_oitTotalTransmittance(_flw_coefficients")
               .append(set)
               .append(", ")
               .append(ranks[set])
               .append(");\n");
        }
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            if (colored && slot == 1) continue;
            int set = coefficientSet(spec, slot);
            if (!colored || slot != 0) out.append("    vec4 texel")
                                          .append(slot).append(" = texelFetch(_flw_accumulate").append(slot)
                                          .append(", pixel, 0);\n");
            out.append("    _flw_frag")
               .append(slot)
               .append(" = ");
            if (set == ContractProperties.Accumulate.FRONTMOST) {
                out.append("texel")
                   .append(slot)
                   .append(";\n");
            } else {
                boolean premultiplied = spec.oit().accumulate(drawBuffers[slot]).premultiplied();
                out.append("texel")
                   .append(slot)
                   .append(".a >= 1e-5 ? vec4(texel")
                   .append(slot)
                   .append(".rgb / texel")
                   .append(slot)
                   .append(premultiplied ? ".a * (1.0 - total" + set + "), 1.0 - total" : ".a, 1.0 - total")
                   .append(set)
                   .append(premultiplied ? ") : vec4(texel" + slot + ".rgb, 0.0);\n" : ") : vec4(0.0);\n");
            }
        }
        if (colored) {
            out.append("    vec4 background = texelFetch(_flw_forwardBackground, ivec2(gl_FragCoord.xy), 0);\n");
            if (packed) out.append("    background.rgb = pow(max(background.rgb, vec3(0.0)), vec3(2.2)) * 120.0;\n");
            out.append("""
                        vec3 tint = exp(logTint);
                        vec3 linearColor = _flw_frag0.rgb * _flw_frag0.a + background.rgb * (1.0 - _flw_frag0.a) * tint;
                    """);
            if (packed) out.append("""
                        vec3 encoded = pow(max(linearColor / 120.0, vec3(0.0)), vec3(1.0 / 2.2));
                        // Later native stages author alpha; this merge changes radiance only.
                        _flw_frag0 = vec4(encoded, background.a);
                    """);
            else out.append("    _flw_frag0 = vec4(linearColor, background.a);\n");
        }
        return out.append("}\n")
                  .toString();
    }

    static String depthFragment(boolean shadow) {
        return GuestShaders.versionPreamble() + """
                uniform sampler2D _flw_depthRange;
                void main() {
                    float depth = texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0).b;
                    if (depth <= 0.0) {
                        discard;
                    }
                    gl_FragDepth = %s;
                }
                """.formatted(shadow && !NativeDepth.active() ? "1.0 - depth" : "depth");
    }

    static String waterFragment(ForwardOit forward) {
        var out = new StringBuilder(GuestShaders.versionPreamble());
        out.append("uniform sampler2D _flw_depthRange;\n");
        for (int i = 0; i < forward.waterBuffers().length; i++)
            out.append(
                       "uniform usampler2D _flw_forwardWaterRead").append(i).append(";\nlayout(location = ")
               .append(i).append(") out vec4 _flw_waterOutput").append(i).append(";\n");
        out.append("void main() {\n    ivec2 pixel = ivec2(gl_FragCoord.xy);\n")
           .append("    float surfaceDepth = texelFetch(_flw_depthRange, pixel, 0).b;\n")
           .append("    if (surfaceDepth <= 0.0) discard;\n");
        for (int i = 0; i < forward.waterBuffers().length; i++)
            out.append("    _flw_waterOutput").append(i)
               .append(" = uintBitsToFloat(texelFetch(_flw_forwardWaterRead").append(i).append(", pixel, 0));\n");
        return out.append("    gl_FragDepth = surfaceDepth;\n}\n").toString();
    }

    private static int coefficientSet(GuestShaders.OitSpec spec, int slot) {
        int set = spec.oit()
                      .accumulate(spec.drawBuffers()[slot])
                      .coefficient();
        if (set >= spec.oit()
                       .ranks().length) {
            throw new IllegalStateException("OIT draw buffer " + spec.drawBuffers()[slot] + " names coefficient set "
                    + set + " but only " + spec.oit()
                                               .ranks().length + " ranks are declared");
        }
        return set;
    }

    private static int firstSlot(GuestShaders.OitSpec spec, int set) {
        for (int slot = 0; slot < spec.drawBuffers().length; slot++) {
            if (coefficientSet(spec, slot) == set) {
                return slot;
            }
        }
        throw new IllegalStateException("OIT coefficient set " + set + " has no draw buffer");
    }
}
