package dev.engine_room.flywheel.backend.vk.shader;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * draw-parameter built-ins ({@code gl_InstanceIndex} already includes {@code firstInstance}, so the GL
 * {@code gl_BaseInstanceARB + gl_InstanceID} pattern must collapse, not rename -- built-ins can't be remapped
 * with {@code #define}, the {@code gl_} namespace is reserved), explicit {@code set}/{@code binding} on every
 * resource, and explicit interface locations.
 */
public final class VkShaderTransform {
    private static final Map<String, Integer> UBO_BINDINGS = Map.ofEntries(
            Map.entry("Projection", 16),
            Map.entry("DynamicTransforms", 17),
            Map.entry("Fog", 18),
            Map.entry("Lighting", 19),
            Map.entry("Globals", 20),
            Map.entry("_FlwInstanceDraw", 21),
            Map.entry("_FlwRenderOrigin", 22),
            Map.entry("_FlwEmbedDraw", 23),
            Map.entry("ChunkSection", 21),
            Map.entry("_FlwFrameUniforms", 16),
            Map.entry("_FlwOptionsUniforms", 17),
            Map.entry("_FlwPlayerUniforms", 18),
            Map.entry("_FlwLevelUniforms", 19),
            Map.entry("_FlwMlabUniforms", 26));
    private static final Map<String, Integer> SAMPLER_BINDINGS = Map.ofEntries(
            Map.entry("Sampler0", 10),
            Map.entry("Sampler1", 11),
            Map.entry("Sampler2", 12),
            Map.entry("_flw_crumblingTex", 13),
            Map.entry("_flw_depthPyramid", 10),
            Map.entry("depth_tex", 10),
            Map.entry("_flw_depthRange", 14),
            Map.entry("_flw_blueNoise", 15),
            Map.entry("_flw_coefficients0", 24),
            Map.entry("_flw_coefficients1", 25),
            Map.entry("_flw_coefficients2", 26),
            Map.entry("_flw_coefficients3", 27),
            Map.entry("_flw_accumulate", 28),
            Map.entry("_flw_layerColor", 29),
            Map.entry("_flw_layerDepth", 30),
            Map.entry("_flw_opaqueDepth", 31),
            Map.entry("_flw_layerColor1", 32),
            Map.entry("_flw_layerDepth1", 33),
            Map.entry("_flw_layerColor2", 34),
            Map.entry("_flw_layerDepth2", 35),
            Map.entry("_flw_layerColor3", 36),
            Map.entry("_flw_layerDepth3", 37));
    private static final Map<String, Integer> VERTEX_INPUT_LOCATIONS = Map.of(
            "Position", 0, "Color", 1, "UV0", 2, "UV1", 3, "UV2", 4, "Normal", 5);
    private static final Map<String, Integer> VARYING_LOCATIONS = Map.ofEntries(
            Map.entry("vertexColor", 0),
            Map.entry("texCoord0", 1),
            Map.entry("lightCoord", 2),
            Map.entry("overlayCoord", 3),
            Map.entry("sphericalVertexDistance", 4),
            Map.entry("cylindricalVertexDistance", 5),
            Map.entry("flw_vertexPos", 6),
            Map.entry("flw_vertexNormal", 7),
            Map.entry("_flw_packedMaterial", 8),
            Map.entry("flw_oitViewZ", 9),
            Map.entry("_flw_clipData", 10),
            Map.entry("_flw_crumblingTexCoord", 12),
            Map.entry("flw_chunkVisibility", 13),
            Map.entry("_flw_texIndex", 14),
            Map.entry("flw_vertexAlpha", 0),
            Map.entry("vertexPerFaceColorBack", 0),
            Map.entry("vertexPerFaceColorFront", 2),
            Map.entry("lightMapColor", 3),
            Map.entry("overlayColor", 6),
            Map.entry("fragColor", 0));
    // GL_KHR_shader_subgroup_* and GL_ARB_fragment_shader_interlock are excluded from the drop: glslang gates their built-ins behind the extension directive even under Vulkan targets (they are NOT core at 460).
    private static final Pattern EXTENSION_LINE = Pattern.compile(
            "(?m)^\\s*#extension\\s+GL_(?!KHR_shader_subgroup|ARB_fragment_shader_interlock)(ARB|NV|KHR)_\\w+\\s*:.*$");
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^\\s*#version\\s+\\d+(\\s+core)?\\s*$");
    private static final Pattern SSBO_LAYOUT = Pattern.compile("layout\\(\\s*std430\\s*,");
    private static final Pattern UBO_BLOCK = Pattern.compile(
            "(?:layout\\(\\s*std140\\s*(?:,\\s*binding\\s*=\\s*\\d+\\s*)?\\)\\s+)?uniform\\s+(\\w+)\\s*\\{");
    private static final Pattern SAMPLER_DECL = Pattern.compile(
            "(?:layout\\([^)]*\\)\\s+)?uniform\\s+sampler2D\\s+(\\w+)\\s*;");
    private static final Pattern INTERFACE_VAR = Pattern.compile(
            "(?m)^(\\s*)(flat\\s+)?(in|out)\\s+(\\w+)\\s+(\\w+)\\s*;");
    private static final Pattern IMAGE_LAYOUT = Pattern.compile("layout\\(\\s*binding\\s*=\\s*(\\d+)\\s*,\\s*r32f");
    private static final Pattern BARE_UNIFORM = Pattern.compile(
            "(?m)^[ \\t]*uniform\\s+(uint|int|float)\\s+(\\w+)\\s*;[ \\t]*$");
    private static final Pattern VERSION_DIRECTIVE = Pattern.compile("(?m)^#version[^\\n]*\\n");
    private VkShaderTransform() {
    }

    public static String toVulkan(String glsl, Stage stage) {
        String out = glsl;

        out = VERSION_LINE.matcher(out).replaceFirst("#version 460");
        out = EXTENSION_LINE.matcher(out).replaceAll("");

        // Order matters: rewrite gl_InstanceID before the ARB renames so it captures the pre-rename token.
        out = replaceToken(out, "gl_InstanceID", "(gl_InstanceIndex - gl_BaseInstance)");
        out = replaceToken(out, "gl_BaseInstanceARB", "gl_BaseInstance");
        out = replaceToken(out, "gl_DrawIDARB", "gl_DrawID");
        out = replaceToken(out, "gl_VertexID", "gl_VertexIndex");

        out = SSBO_LAYOUT.matcher(out).replaceAll("layout(set = 0, std430,");

        out = decorateBlocks(out, UBO_BLOCK, name -> {
            Integer b = UBO_BINDINGS.get(name);
            return b == null ? null : "layout(std140, set = 0, binding = " + b + ") uniform " + name + " {";
        });

        out = decorateBlocks(out, SAMPLER_DECL, name -> {
            Integer b = SAMPLER_BINDINGS.get(name);
            return b == null ? null : "layout(set = 0, binding = " + b + ") uniform sampler2D " + name + ";";
        });

        out = IMAGE_LAYOUT.matcher(out).replaceAll("layout(set = 0, binding = $1, r32f");

        if (stage == Stage.COMPUTE) {
            out = foldPushConstants(out);
        }

        out = decorateInterface(out, stage);

        return out;
    }

    private static String replaceToken(String src, String token, String replacement) {
        return src.replaceAll("\\b" + Pattern.quote(token) + "\\b", Matcher.quoteReplacement(replacement));
    }

    // Collect bare scalar uniforms into one push_constant block placed after #version; member order = source order, matching the host's vkCmdPushConstants offsets.
    private static String foldPushConstants(String src) {
        Matcher m = BARE_UNIFORM.matcher(src);
        StringBuilder members = new StringBuilder();
        boolean any = false;
        while (m.find()) {
            members.append("    ").append(m.group(1)).append(' ').append(m.group(2)).append(";\n");
            any = true;
        }
        if (!any) {
            return src;
        }
        String stripped = BARE_UNIFORM.matcher(src).replaceAll("");
        String block = "layout(push_constant) uniform _FlwDownsamplePush {\n" + members + "};\n";
        Matcher v = VERSION_DIRECTIVE.matcher(stripped);
        if (v.find()) {
            return stripped.substring(0, v.end()) + block + stripped.substring(v.end());
        }
        return block + stripped;
    }

    private static String decorateBlocks(String src, Pattern pattern, Decorator decorator) {
        Matcher m = pattern.matcher(src);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String replacement = decorator.decorate(name);
            m.appendReplacement(sb,
                    replacement == null ? Matcher.quoteReplacement(m.group()) : Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String decorateInterface(String src, Stage stage) {
        Matcher m = INTERFACE_VAR.matcher(src);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String indent = m.group(1);
            String flat = m.group(2) == null ? "" : m.group(2);
            String dir = m.group(3);
            String type = m.group(4);
            String name = m.group(5);

            Integer location = locationFor(stage, dir, name);
            if (location == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String decorated = indent + "layout(location = " + location + ") " + flat + dir + " " + type + " " + name + ";";
            m.appendReplacement(sb, Matcher.quoteReplacement(decorated));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Integer locationFor(Stage stage, String dir, String name) {
        if (stage == Stage.VERTEX && dir.equals("in")) {
            return VERTEX_INPUT_LOCATIONS.get(name);
        }
        return VARYING_LOCATIONS.get(name);
    }

    public enum Stage {
        VERTEX, FRAGMENT, COMPUTE
    }

    private interface Decorator {
        String decorate(String name);
    }
}
