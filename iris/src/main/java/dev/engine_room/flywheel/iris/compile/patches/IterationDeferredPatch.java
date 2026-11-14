package dev.engine_room.flywheel.iris.compile.patches;

import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.selection.SelectionStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ReturnStatement;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import static dev.engine_room.flywheel.iris.compile.patches.DeferredSources.*;

/**
 * Inline deferred resolve for IterationRP's translucent composition pass.
 */
final class IterationDeferredPatch {
    // Captured records: albedo halves, then gtransData and gtransNormal, then the water target.
    static final String ALBEDO = "vec4(unpackHalf2x16(flw_nodes[flw_farNode].z), unpackHalf2x16(flw_nodes[flw_farNode].w))";
    static final String DATA = "vec4(unpackUnorm2x16(flw_nodes[flw_farNode + 1u].x), unpackUnorm2x16(flw_nodes[flw_farNode + 1u].y))";
    static final String NORMAL = "vec4(unpackUnorm2x16(flw_nodes[flw_farNode + 1u].z), unpackUnorm2x16(flw_nodes[flw_farNode + 1u].w))";
    private static final String WATER = "vec4(unpackUnorm2x16(flw_nodes[flw_farNode + 2u].x), unpackUnorm2x16(flw_nodes[flw_farNode + 2u].y))";
    static final String NODE_DEPTH = "uintBitsToFloat(flw_nodes[flw_farNode].y)";
    // LoD depth buffers hold the LoD projection; light lists compare in the main one, past its far plane outside
    // [0, 1].
    static final String LOD_BOUND_PROTOTYPE = "float flw_lodBound(float depth, float lod);";
    static final String LOD_BOUND = "float flw_lodBound(float depth, float lod) {"
            + " return depth == (_FLW_REVERSED_DEPTH ? 0.0 : 1.0) ? ScreenDepth_From_LODScreenDepth(lod) : depth; }";
    private static final Set<String> BUILTIN_READS = Set.of("texelFetch", "textureLod", "texture");
    // Printed form: float literals print as Double.toString plus the float suffix.
    private static final Pattern REVERSED_LOD_ENCODING = Pattern.compile("2(?:\\.0f)?\\s*-");

    private IterationDeferredPatch() {
    }

    /**
     * Composes every captured surface behind the native front surface where the pass reads its lit opaque
     * background, shading each with the pass's own body. Temporal depth stores keep the native opaque depth.
     * Rear surfaces reflect their {@link IterationRearReflection} trace. {@code reversed}: the pack's sources are
     * spelled in raw reversed depth.
     */
    static String resolve(String source, boolean reversed) {
        return transform(source, (tree, root, parser) -> {
            if (root.identifierIndex.has("fsrPixelSize") || root.identifierIndex.has("fsrRenderScale"))
                throw unsupported("Iteration light layers require native resolution");
            var main = function(root, "main");
            var refraction = function(root, "GlassRefraction");
            // Under LoD rendering the pack substitutes encoded LoD depths where the main depth buffers hold sky:
            // negated, or 2 - lod in raw reversed depth.
            var lodFront = lodRemap(main, root, "depth", reversed);
            var lodOpaque = lodRemap(main, root, "opaqueDepth", reversed);
            if (lodFront != null && !(reversed ? REVERSED_LOD_ENCODING.matcher(lodFront.value()).lookingAt()
                    : lodFront.value().startsWith("-"))) throw unsupported("Iteration LoD depth encoding");
            var opaque = member(tree, root, "depthtexS").getAncestor(TypeAndInitDeclaration.class);
            boolean unsigned = ASTPrinter.printSimple(opaque.getType()).contains("usampler");
            ShaderDeclarations.uniform(tree, root, parser, "vec2", "screenSize");
            ShaderDeclarations.uniform(tree, root, parser, "sampler2D", "pixelData2D");
            inject(tree, parser,
                    DeferredOitProfile.independentNodeAccess(DeferredOitProfile.resource("iteration_layers.glsl"))
                            + DeferredOitProfile.resource("iteration_materials.glsl")
                            + DeferredOitProfile.resource("iteration_light_resolve.glsl"),
                    ASTInjectionPoint.BEFORE_DECLARATIONS);
            tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "vec3 flw_resolveRefractedLight(vec3 background, vec2 coord, float back, vec4 plane);",
                    "vec3 _flw_farColor;", "uint _flw_rearNode;");
            if (lodOpaque != null) {
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, LOD_BOUND_PROTOTYPE);
                tree.parseAndInjectNode(parser, ASTInjectionPoint.END, LOD_BOUND);
            }

            var decode = graft(tree, root, parser, function(root, "GetGbufferDataTranslucent"), "GbufferData",
                    "_flw_rearGetGbufferDataTranslucent", "uint flw_farNode");
            if (redirect(decode, root, parser, Map.of("colortex0", ALBEDO, "colortex3", DATA, "colortex4", NORMAL)) < 3)
                throw unsupported("Iteration translucent decode reads");
            var rearRefraction = graft(tree, root, parser, refraction, "vec2", "_flw_rearGlassRefraction",
                    "uint flw_farNode, float flw_farBack");
            redirect(rearRefraction, root, parser, Map.of("colortex6", "vec4(_flw_farColor, 1.0)", "colortex5", WATER));
            opaqueDepth(rearRefraction, unsigned, root, parser, _ -> "flw_farBack");
            var merge = graft(tree, root, parser, function(root, "MergeSpecular"), "void", "_flw_rearMergeSpecular",
                    "");
            // A refraction path may reach another pixel's native surface: it has the pack's own filtered trace.
            if (redirect(merge, root, parser, Map.of("colortex8", "(uintBitsToFloat(flw_nodes[_flw_rearNode].y) == "
                    + (lodFront == null ? "texelFetch(depthtex0, texelCoord, 0).x"
                    : "flw_lodBound(texelFetch(depthtex0, texelCoord, 0).x, texelFetch("
                    + lodFront.sampler() + ", texelCoord, 0).x)") + " ? texelFetch(colortex8, texelCoord, 0) : "
                    + RearReflectionCompute.filtered("_flw_rearNode", IterationRearReflection.SLOT) + ")")) != 1)
                throw unsupported("Iteration surface reflection read");

            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "vec3 _flw_farLayer(uint flw_farNode, ivec2 texel, vec2 texcoord, vec3 flw_farBackground, float flw_farBack) {}");
            var far = (FunctionDefinition) tree.getChildren().getLast();
            far.getBody().replaceByAndDelete(main.getBody().cloneInto(root));
            rear(far, unsigned, lodOpaque, root, parser);
            // Fully opaque texels of translucent blocks reach the pack's own opaque depth, not depthtex1.
            String opaqueBound = unsigned ? "uintBitsToFloat(texelFetch(depthtexS, pixel, 0).x)"
                    : "texelFetch(depthtexS, pixel, 0).x";
            if (lodOpaque != null) opaqueBound = "flw_lodBound(" + opaqueBound + ", texelFetch("
                    + lodOpaque.sampler() + ", pixel, 0).x)";
            walker(tree, parser, "vec3", "ViewPos_From_ScreenPos(coord, depth)", "flw_materialHeads", opaqueBound);
            inject(tree, parser, DeferredOitProfile.resource("iteration_refracted_light.glsl"), ASTInjectionPoint.END);

            front(main, refraction, unsigned, lodFront, lodOpaque, root, parser);
        });
    }

    /**
     * The pack's LoD substitution {@code if (variable == sky) {variable = value; ...}} among {@code main}'s
     * statements, or null without LoD rendering. {@code reversed}: sky is spelled in raw reversed depth.
     */
    static LodRemap lodRemap(FunctionDefinition main, Root root, String variable, boolean reversed) {
        if (!root.identifierIndex.has("lodProjection0")) return null;
        String sky = variable + (reversed ? "==0.0f" : "==1.0f");
        for (var statement : main.getBody().getStatements()) {
            if (!(statement instanceof SelectionStatement selection) || !ASTPrinter.printSimple(
                                                                                           selection.getCondition())
                                                                                   .replaceAll("\\s", "")
                                                                                   .equals(sky))
                continue;
            var assignments = root.nodeIndex.getStream(AssignmentExpression.class)
                                            .filter(assignment -> assignment.hasAncestor(selection.getIfTrue())
                                                    && ASTPrinter.printSimple(assignment.getLeft()).strip()
                                                                 .equals(variable)).toList();
            if (assignments.size() != 1) break;
            var value = assignments.getFirst().getRight();
            var reads = calls(value, root, "texelFetch");
            if (reads.size() != 1) break;
            return new LodRemap(selection, ASTPrinter.printSimple(value).strip(),
                    ASTPrinter.printSimple(reads.getFirst().getParameters().getFirst()).strip());
        }
        throw unsupported("Iteration LoD " + variable);
    }

    /**
     * {@code value} replaces a sky depth with {@code sampler}'s LoD depth at the pass's own texel.
     */
    record LodRemap(SelectionStatement statement, String value, String sampler) {
    }

    private static void rear(FunctionDefinition far, boolean unsigned, LodRemap lodOpaque, Root root,
                             ASTParser parser) {
        var statements = far.getBody().getStatements();
        for (var call : calls(far, root, "GetGbufferDataTranslucent"))
            call.replaceByAndDelete(parser.parseExpression(root, "_flw_rearGetGbufferDataTranslucent("
                    + arguments(call.getParameters()) + ", flw_farNode)"));
        for (var call : calls(far, root, "GlassRefraction"))
            call.replaceByAndDelete(parser.parseExpression(root, "_flw_rearGlassRefraction("
                    + arguments(call.getParameters()) + ", flw_farNode, flw_farBack)"));
        for (var call : calls(far, root, "MergeSpecular"))
            call.replaceByAndDelete(parser.parseExpression(root, "_flw_rearMergeSpecular("
                    + arguments(call.getParameters()) + ")"));
        redirect(far, root, parser, Map.of("depthtex0", "vec4(" + NODE_DEPTH + ")"));
        opaqueDepth(far, unsigned, root, parser, _ -> "flw_farBack");
        // History, velocity and output stores belong to the front surface.
        for (var call : calls(far, root, "imageStore")) {
            var statement = call.getAncestor(ExpressionStatement.class);
            if (statement == null || statement.getExpression() != call) throw unsupported("Iteration image store");
            statement.replaceByAndDelete(parser.parseStatement(root, ";"));
        }
        if (root.nodeIndex.getStream(ReturnStatement.class).anyMatch(statement -> statement.hasAncestor(far)))
            throw unsupported("Iteration composition has an early exit");
        statements.addAll(0, List.of(parser.parseStatement(root, "ivec2 flw_savedTexel = texelCoord;"),
                parser.parseStatement(root, "vec2 flw_savedCoord = texCoord;"),
                parser.parseStatement(root, "texelCoord = texel;"),
                parser.parseStatement(root, "texCoord = texcoord;"),
                parser.parseStatement(root, "_flw_rearNode = flw_farNode;"),
                parser.parseStatement(root, "_flw_farColor = flw_resolveLight(flw_farBackground, texel, "
                        + NODE_DEPTH + ", " + (lodOpaque == null ? "flw_farBack" : "flw_lodBound(flw_farBack, texelFetch("
                        + lodOpaque.sampler() + ", texel, 0).x)") + ", uvec2(screenSize));")));
        statements.addAll(List.of(parser.parseStatement(root, "texelCoord = flw_savedTexel;"),
                parser.parseStatement(root, "texCoord = flw_savedCoord;"),
                parser.parseStatement(root, "return color;")));
    }

    private static void front(FunctionDefinition main, FunctionDefinition refraction, boolean unsigned,
                              LodRemap lodFront, LodRemap lodOpaque, Root root, ASTParser parser) {
        var statements = main.getBody().getStatements();
        int opaque = declaration(statements, "opaqueDepth");
        if (declaration(statements, "depth") != 0 || opaque != 1) throw unsupported("Iteration depth inputs");
        // Temporal reprojection keeps the native opaque depth; the composition sees the nearest rear surface.
        if (!(member(main, root, "opaqueDepth").getInitializer() instanceof ExpressionInitializer initializer))
            throw unsupported("Iteration opaque depth");
        statements.add(opaque, parser.parseStatement(root, "float flw_nativeOpaqueDepth = "
                + ASTPrinter.printSimple(initializer.getExpression()) + ';'));
        String frontBound = lodFront == null ? "depth" : "flw_lodBound(depth, texelFetch("
                + lodFront.sampler() + ", texelCoord, 0).x)";
        String opaqueBound = lodOpaque == null ? "flw_nativeOpaqueDepth"
                : "flw_lodBound(flw_nativeOpaqueDepth, texelFetch(" + lodOpaque.sampler() + ", texelCoord, 0).x)";
        initializer.setExpression(parser.parseExpression(root,
                "flw_farDepth(texelCoord, " + frontBound + ", vec4(0.0), false, " + opaqueBound + ")"));
        if (lodOpaque != null) statements.add(statements.indexOf(lodOpaque.statement()) + 1, parser.parseStatement(root,
                "if (flw_nativeOpaqueDepth == (_FLW_REVERSED_DEPTH ? 0.0 : 1.0)) flw_nativeOpaqueDepth = "
                        + lodOpaque.value() + ';'));
        int history = 0;
        for (var call : calls(main, root, "imageStore")) {
            String target = ASTPrinter.printSimple(call.getParameters().getFirst()).strip();
            if (!target.equals("img_prevDepth2D")) continue;
            rename(call, root, "opaqueDepth", "flw_nativeOpaqueDepth");
            history++;
        }
        if (history != 1) throw unsupported("Iteration previous depth store");
        rename(member(main, root, "referenceDepth"), root, "opaqueDepth", "flw_nativeOpaqueDepth");

        var body = refraction.getBody().getStatements();
        body.addAll(0, List.of(
                parser.parseStatement(root, "vec3 flw_planeNormal = vertexNormal * mat3(gbufferModelViewInverse);"),
                parser.parseStatement(root, "flw_planeNormal *= dot(flw_planeNormal, viewPos) < 0.0 ? -1.0 : 1.0;"),
                parser.parseStatement(root,
                        "vec4 flw_farPlane = vec4(flw_planeNormal, -dot(flw_planeNormal, viewPos));")));
        String front = lodOpaque == null ? "depth" : "(_FLW_REVERSED_DEPTH"
                + " ? (depth > 1.0 ? flw_lodBound(0.0, 2.0 - depth) : depth)"
                + " : (depth < 0.0 ? flw_lodBound(1.0, -depth) : depth))";
        for (var read : samplerReads(refraction, root, "colortex6")) {
            String at = ASTPrinter.printSimple(read.getParameters().get(1)).strip();
            boolean own = at.equals("texelCoord");
            read.replaceByAndDelete(parser.parseExpression(root, "vec4(flw_farComposite(" + (own ? "texelCoord"
                    : "ivec2((" + at + ") * screenSize)") + ", " + front + ", flw_farPlane, " + !own + ", "
                    + ASTPrinter.printSimple(read) + ".rgb), 1.0)"));
        }
        opaqueDepth(refraction, unsigned, root, parser, read -> "flw_farDepth(ivec2((" + ASTPrinter.printSimple(
                                                                                                           read.parameters().get(1))
                                                                                                   .strip() + ") * screenSize), " + front + ", flw_farPlane, true, "
                + (lodOpaque == null ? read.value() : "flw_lodBound(" + read.value() + ", texture("
                + lodOpaque.sampler() + ", " + ASTPrinter.printSimple(read.parameters().get(1)) + ").x)") + ")");
        // Light-only surfaces between the front surface and the nearest composed one.
        var exit = body.getLast();
        if (!(exit instanceof ReturnStatement)) throw unsupported("Iteration refraction exit");
        String targetOpaque = unsigned ? "uintBitsToFloat(texelFetch(depthtexS, ivec2(flw_coord * screenSize), 0).x)"
                : "texelFetch(depthtexS, ivec2(flw_coord * screenSize), 0).x";
        if (lodOpaque != null) {
            targetOpaque = "flw_lodBound(" + targetOpaque + ", texelFetch(" + lodOpaque.sampler()
                    + ", ivec2(flw_coord * screenSize), 0).x)";
        }
        body.add(body.size() - 1, parser.parseStatement(root, """
                {
                    vec2 flw_coord = refractCoord;
                    float flw_back = flw_farDepth(ivec2(flw_coord * screenSize), %s, flw_farPlane, true, %s);
                    color = mask.water + mask.stainedGlass > 0.5
                            ? flw_resolveRefractedLight(color, flw_coord, flw_back, flw_farPlane)
                            : flw_resolveLight(color, ivec2(flw_coord * screenSize), %s, flw_back, uvec2(screenSize));
                }
                """.formatted(front, targetOpaque, front)));
    }

    /**
     * Replaces opaque-depth reads in {@code scope} by {@code value}, given the read as a float expression. Builtin
     * fetches of an unsigned sampler return float bits; the legacy-ABI helpers return floats.
     */
    private static void opaqueDepth(FunctionDefinition scope, boolean unsigned, Root root, ASTParser parser,
                                    Function<Read, String> value) {
        for (var read : samplerReads(scope, root, "depthtexS")) {
            boolean bits = unsigned && BUILTIN_READS.contains(read.getFunctionName().getName());
            String name = ASTPrinter.printSimple(read) + ".x";
            String depth = value.apply(new Read(bits ? "uintBitsToFloat(" + name + ")" : name, read.getParameters()));
            read.replaceByAndDelete(parser.parseExpression(root, bits ? "uvec4(floatBitsToUint(" + depth + "))"
                    : "vec4(" + depth + ")"));
        }
    }

    /**
     * An opaque-depth read: its float value expression and the original call arguments.
     */
    private record Read(String value, List<? extends ASTNode> parameters) {
    }

    private static void rename(ASTNode scope, Root root,
                               String from, String to) {
        for (var identifier : root.identifierIndex.getStream(from).toList())
            if (identifier.hasAncestor(scope)) identifier.setName(to);
    }

    private static String arguments(List<? extends ASTNode> parameters) {
        return String.join(", ", parameters.stream().map(ASTPrinter::printSimple).toList());
    }
}
