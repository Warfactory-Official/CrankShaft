package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Colorwheel OIT stages: producer {@code main}s wrapping the pack's (outputs demoted to globals, keyed by location)
 * and the composite. Pack draw buffer slot {@code s} = output location {@code s}.
 */
final class GuestOitCodegen {
    static final Identifier LIBRARY = ResourceUtil.rl("iris/guest_oit.glsl");

    static final String COMPOSITE_VERTEX = """
            #version 460 core
            void main() {
                vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private GuestOitCodegen() {
    }

    /**
     * Encoded device depth in the depth range's blue channel: MAX-blended, so nearer must be larger.
     */
    private static String deviceDepth(boolean shadow) {
        return shadow ? "(1.0 - gl_FragCoord.z)" : "gl_FragCoord.z";
    }

    static String producer(GuestShaders.OitSpec spec, Map<Integer, String> outputs) {
        StringBuilder out = new StringBuilder();
        switch (spec.pass()) {
            case DEPTH_RANGE -> out.append("layout(location = 0) out vec4 _flw_oitDepthRange;\n")
                                   .append("void main() {\n    _flw_irisMain();\n")
                                   .append("    float linearDepth = -_flw_oitViewZ;\n")
                                   .append("    _flw_oitDepthRange = vec4(-linearDepth + 1e-5, linearDepth + 1e-2, ")
                                   .append(deviceDepth(spec.shadow()))
                                   .append(", 0.0);\n}\n");
            case COEFFICIENTS -> coefficients(out, spec, outputs);
            case EVALUATE -> evaluate(out, spec, outputs);
        }
        return out.toString();
    }

    private static void normalizedDepth(StringBuilder out) {
        out.append("    _flw_irisMain();\n")
           .append("    float linearDepth = -_flw_oitViewZ;\n")
           .append("    vec2 depthRange = texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0).rg;\n")
           .append("    float depth = (linearDepth + depthRange.x) / (depthRange.x + depthRange.y);\n")
           .append("    float adjustment = _flw_oitTentedBlueNoise(depth) * _FLW_GUEST_OIT_NOISE;\n");
    }

    private static void coefficients(StringBuilder out, GuestShaders.OitSpec spec, Map<Integer, String> outputs) {
        int[] ranks = spec.oit()
                          .ranks();
        int attachment = 0;
        for (int rank : ranks) {
            for (int layer = 0; layer < GuestOitTargets.layers(rank); layer++) {
                out.append("layout(location = ")
                   .append(attachment)
                   .append(") out vec4 _flw_oitCoefficients")
                   .append(attachment++)
                   .append(";\n");
            }
        }
        out.append("void main() {\n");
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
            for (int layer = 0; layer < GuestOitTargets.layers(ranks[set]); layer++) {
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
        for (int set = 0; set < ranks.length; set++) {
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
        normalizedDepth(out);
        out.append("    float frontmost = linearDepth <= -depthRange.x + 2e-5 ? 1.0 : 0.0;\n");
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            String source = outputs.get(slot);
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
        int[] ranks = spec.oit()
                          .ranks();
        int[] drawBuffers = spec.drawBuffers();
        StringBuilder out = new StringBuilder("#version 460 core\n");
        out.append(FlwPrograms.SOURCES.get(LIBRARY)
                                      .source())
           .append('\n');
        for (int set = 0; set < ranks.length; set++) {
            out.append("uniform sampler2DArray _flw_coefficients")
               .append(set)
               .append(";\n");
        }
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            out.append("uniform sampler2D _flw_accumulate")
               .append(slot)
               .append(";\nlayout(location = ")
               .append(slot)
               .append(") out vec4 _flw_frag")
               .append(slot)
               .append(";\n");
        }
        out.append("void main() {\n")
           .append("    vec4 range = texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0);\n")
           .append("    if (range.b <= 0.0) {\n        discard;\n    }\n")
           .append("    gl_FragDepth = ")
           .append(spec.shadow() ? "1.0 - range.b" : "range.b")
           .append(";\n");
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
            int set = coefficientSet(spec, slot);
            out.append("    vec4 texel")
               .append(slot)
               .append(" = texelFetch(_flw_accumulate")
               .append(slot)
               .append(", ivec2(gl_FragCoord.xy), 0);\n    _flw_frag")
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
        return out.append("}\n")
                  .toString();
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
