package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.compile.FlwPrograms;
import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;

import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.util.ResourceLocation;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

final class GuestOitCodegen {
    static final ResourceLocation LIBRARY = ResourceUtil.rl("shaderpack/guest_oit.glsl");

    static final String COMPOSITE_VERTEX = GuestShaders.versionPreamble() + """
            void main() {
                vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private GuestOitCodegen() {
    }

    static String library() {
        return FlwPrograms.SOURCES.get(LIBRARY).source();
    }

    static int layers(int rank) {
        return (1 << (rank + 1)) / 4;
    }

    private static String deviceDepth() {
        // 1.12.2: GL_MAX needs reversed device depth to retain the closest fragment.
        return "(1.0 - gl_FragCoord.z)";
    }

    static String producer(GuestShaders.OitSpec spec, Int2ObjectMap<String> outputs) {
        StringBuilder out = new StringBuilder();
        switch (spec.pass()) {
            case DEPTH_RANGE -> out.append("layout(location = 0) out vec4 _flw_oitDepthRange;\n")
                                   .append("void main() {\n    _flw_irisMain();\n")
                                   .append("    float linearDepth = -_flw_oitViewZ;\n")
                                   .append("    _flw_oitDepthRange = vec4(-linearDepth + 1e-5, linearDepth + 1e-2, ")
                                   .append(deviceDepth())
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

    private static void coefficients(StringBuilder out, GuestShaders.OitSpec spec, Int2ObjectMap<String> outputs) {
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
        normalizedDepth(out);
        attachment = 0;
        for (int set = 0; set < ranks.length; set++) {
            int slot = firstSlot(spec, set);
            if (slot < 0) {
                for (var layer = 0; layer < layers(ranks[set]); layer++) {
                    out.append("    _flw_oitCoefficients").append(attachment++).append(" = vec4(0.0);\n");
                }
                continue;
            }
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

    private static void evaluate(StringBuilder out, GuestShaders.OitSpec spec, Int2ObjectMap<String> outputs) {
        int[] ranks = spec.oit()
                          .ranks();
        for (int set = 0; set < ranks.length; set++) {
            out.append("uniform sampler2DArray _flw_coefficients")
               .append(set)
               .append(";\n");
        }
        int[] drawBuffers = spec.drawBuffers();
        for (int slot = 0; slot < spec.oit().drawBuffers().length; slot++) {
            out.append("layout(location = ")
               .append(slot)
               .append(") out vec4 _flw_oitAccumulate")
               .append(slot)
               .append(";\n");
        }
        out.append("void main() {\n");
        for (var slot = 0; slot < spec.oit().drawBuffers().length; slot++) {
            out.append("    _flw_oitAccumulate").append(slot).append(" = vec4(0.0);\n");
        }
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
            if (set == GuestOitContract.FRONTMOST) {
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
               .append(spec.oit().slot(drawBuffers[slot]))
               .append(" = v;\n    }\n");
        }
        out.append("}\n");
    }

    static String compositeFragment(GuestShaders.OitSpec spec) {
        int[] ranks = spec.oit()
                          .ranks();
        int[] drawBuffers = spec.drawBuffers();
        StringBuilder out = new StringBuilder(GuestShaders.versionPreamble());
        out.append("uniform vec2 _flw_oitViewportOrigin;\n");
        out.append(GuestShaders.libraryPreamble());
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
           .append("    if (range.b <= 0.0) {\n        discard;\n    }\n");
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
            if (set == GuestOitContract.FRONTMOST) {
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
        var ast = GlslAst.parse(out.append("}\n").toString());
        ast.replaceExpression("ivec2(gl_FragCoord.xy)", "ivec2(gl_FragCoord.xy-_flw_oitViewportOrigin)", true);
        return ast.source();
    }

    static String depthFragment() {
        return GuestShaders.versionPreamble() + """
                uniform sampler2D _flw_depthRange;
                uniform vec2 _flw_oitViewportOrigin;
                void main() {
                    float depth = texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy - _flw_oitViewportOrigin), 0).b;
                    if (depth <= 0.0) {
                        discard;
                    }
                    gl_FragDepth = %s;
                }
                """.formatted("1.0 - depth");
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
        return -1;
    }
}
