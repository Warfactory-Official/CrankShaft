package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import net.irisshaders.iris.shaderpack.parsing.ConstDirectiveParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Makes the legacy pack's integer image views explicit without changing its voxel or depth encodings.
 */
public final class IterationImageAbi {
    private static final Map<String, Pattern> PROPERTIES = Map.of(
            "voxelColor3D", property("voxelColor3D", "RGBA\\h+RGBA8\\h+UNSIGNED_BYTE"),
            "waterDepth2D", property("waterDepth2D", "RED\\h+R32F\\h+FLOAT"),
            "rtwImportance2D", property("rtwImportance2D", "RED\\h+R32F\\h+FLOAT"),
            "depthtexS", property("depthtexS", "RED\\h+R32F\\h+FLOAT"));
    private static final Set<String> DEPTH_HELPERS = Set.of("SampleDepthFetch", "SampleDepthFetchClosest3x3",
            "SampleCurrDepthFetchClosest3x3");
    private static final Pattern DIRECTIVE_COMMENT = Pattern.compile("/\\*\\s*[A-Z][A-Z0-9_]*:.*?\\*/", Pattern.DOTALL);
    private static final String FLOAT_FETCH = "_flw_iterationFloatFetch";
    private static final String FLOAT_LOD = "_flw_iterationFloatLod";
    private static final String VOXEL_FETCH = "_flw_iterationVoxelFetch";

    private IterationImageAbi() {
    }

    private static Pattern property(String name, String format) {
        return Pattern.compile("(?m)^(\\h*image\\.img_" + name + "\\h*=\\h*" + name + "\\h+)" + format + "(?=\\h)");
    }

    /**
     * Called only after the existing Iteration source certification succeeds.
     */
    public static boolean matches(String source) {
        return PROPERTIES.values().stream().allMatch(pattern -> pattern.matcher(source).find());
    }

    public static String properties(String source) {
        for (var pattern : PROPERTIES.values())
            source = pattern.matcher(source).replaceAll("$1RED_INTEGER R32UI UNSIGNED_INT");
        return source;
    }

    /**
     * Runs after native preprocessing, for native and guest graphics and compute programs alike.
     * glsl-transformer's active build-root stack is process-global, so parallel native source reads must serialize
     * this preparation step even though each call owns its parser.
     */
    public static synchronized String source(String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            var samplers = new HashMap<String, Boolean>();
            var images = new HashMap<String, Boolean>();
            for (var external : tree.getChildren().stream().toList()) {
                if (!(external instanceof DeclarationExternalDeclaration declaration)
                        || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)) continue;
                String type = ASTPrinter.printSimple(typed.getType().getTypeSpecifier()).strip();
                for (var member : typed.getMembers()) {
                    String name = member.getName().getName();
                    if (PROPERTIES.containsKey(name) && (type.equals("sampler2D") || type.equals("sampler3D"))) {
                        samplers.put(name, name.equals("voxelColor3D"));
                        external.replaceByAndDelete(parser.parseExternalDeclaration(root,
                                ASTPrinter.printSimple(external).replaceAll("\\bsampler([23]D)\\b", "usampler$1")));
                        break;
                    } else if (name.startsWith("img_") && PROPERTIES.containsKey(name.substring(4))
                            && (type.equals("image2D") || type.equals("image3D"))) {
                        images.put(name, name.equals("img_voxelColor3D"));
                        external.replaceByAndDelete(parser.parseExternalDeclaration(root,
                                ASTPrinter.printSimple(external).replaceAll("\\b(?:r32f|rgba8)\\b", "r32ui")
                                          .replaceAll("\\bimage([23]D)\\b", "uimage$1")));
                        break;
                    }
                }
            }
            var integerHelpers = new HashSet<FunctionDefinition>();
            for (var function : root.nodeIndex.getStream(FunctionDefinition.class).toList()) {
                if (!DEPTH_HELPERS.contains(function.getFunctionPrototype().getName().getName())) continue;
                var copy = (FunctionDefinition) parser.parseExternalDeclaration(root,
                        ASTPrinter.printSimple(function).replaceAll("\\bsampler2D\\b", "usampler2D"));
                tree.getChildren().add(tree.getChildren().indexOf(function) + 1, copy);
                integerHelpers.add(copy);
            }
            boolean floatFetch = false;
            boolean floatLod = false;
            boolean voxelFetch = false;
            for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (call.getFunctionName() == null || call.getParameters().isEmpty()
                        || !(call.getParameters().getFirst() instanceof ReferenceExpression reference)) continue;
                String sampler = reference.getIdentifier().getName();
                String function = call.getFunctionName().getName();
                Boolean voxel = samplers.get(sampler);
                if (voxel == null && sampler.equals("depthSampler")
                        && integerHelpers.contains(call.getAncestor(FunctionDefinition.class))) voxel = false;
                if (voxel != null && function.equals("texelFetch")) {
                    call.getFunctionName().setName(voxel ? VOXEL_FETCH : FLOAT_FETCH);
                    voxelFetch |= voxel;
                    floatFetch |= !voxel;
                } else if (voxel != null && !voxel && function.equals("textureLod")) {
                    call.getFunctionName().setName(FLOAT_LOD);
                    floatLod = true;
                } else if (function.equals("imageStore") && images.containsKey(sampler)) {
                    var value = call.getParameters().get(2);
                    String encoded = ASTPrinter.printSimple(value);
                    value.replaceByAndDelete(parser.parseExpression(root, images.get(sampler)
                            ? "uvec4(packUnorm4x8(" + encoded + "), 0u, 0u, 0u)"
                            : "floatBitsToUint(" + encoded + ')'));
                }
            }
            if (floatFetch) tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS, """
                    vec4 _flw_iterationFloatFetch(usampler2D image, ivec2 position, int lod) {
                        return vec4(uintBitsToFloat(texelFetch(image, position, lod).r), 0.0, 0.0, 1.0);
                    }
                    """);
            if (voxelFetch) tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS, """
                    vec4 _flw_iterationVoxelFetch(usampler3D image, ivec3 position, int lod) {
                        return unpackUnorm4x8(texelFetch(image, position, lod).r);
                    }
                    """);
            // Integer samplers cannot interpolate the encoded IEEE bits. Preserve the old float sampler's filtering.
            if (floatLod) tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS, """
                    vec4 _flw_iterationFloatLod(usampler2D image, vec2 coordinate, float lod) {
                        int level = int(lod);
                        ivec2 size = textureSize(image, level);
                        vec2 position = coordinate * vec2(size) - 0.5;
                        ivec2 base = ivec2(floor(position));
                        vec2 weight = fract(position);
                        ivec2 limit = size - 1;
                        float a = uintBitsToFloat(texelFetch(image, clamp(base, ivec2(0), limit), level).r);
                        float b = uintBitsToFloat(texelFetch(image, clamp(base + ivec2(1, 0), ivec2(0), limit), level).r);
                        float c = uintBitsToFloat(texelFetch(image, clamp(base + ivec2(0, 1), ivec2(0), limit), level).r);
                        float d = uintBitsToFloat(texelFetch(image, clamp(base + ivec2(1), ivec2(0), limit), level).r);
                        return vec4(mix(mix(a, b, weight.x), mix(c, d, weight.x), weight.y), 0.0, 0.0, 1.0);
                    }
                    """);
        });
        // Iris also reads const directives inside comments. Re-emit its original semantic stream last so AST
        // printing cannot discard metadata or change precedence between active and commented declarations.
        var transformed = new StringBuilder(parser.transform(source)).append("\n/*\n");
        for (var directive : ConstDirectiveParser.findDirectives(source))
            transformed.append("const ").append(directive.getType().name().toLowerCase(Locale.ROOT)).append(' ')
                       .append(directive.getKey()).append(" = ").append(directive.getValue()).append(";\n");
        transformed.append("*/\n");
        DIRECTIVE_COMMENT.matcher(source).results().forEach(match -> transformed.append(match.group()).append('\n'));
        return transformed.toString();
    }
}
