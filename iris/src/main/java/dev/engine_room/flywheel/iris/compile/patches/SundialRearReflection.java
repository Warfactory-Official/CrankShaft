package dev.engine_room.flywheel.iris.compile.patches;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.List;

import static dev.engine_room.flywheel.iris.compile.patches.DeferredSources.*;

/**
 * Rear-surface reflections for Sundial, ahead of the resolve pass: compute passes built from it and the reflection
 * stage. The reflection adds light independent of the surface's background: mirror light, and the rough reflection
 * times its weight. Records carry the rough reflection in their third slot, mirror light and weight in the fourth.
 */
final class SundialRearReflection {
    static final String SLOT = "2u";
    static final String LIGHT = "vec3(unpackHalf2x16(flw_nodes[flw_farNode + 3u].x), "
            + "unpackHalf2x16(flw_nodes[flw_farNode + 3u].y).x)";
    static final String WEIGHT = "vec3(unpackHalf2x16(flw_nodes[flw_farNode + 3u].y).y, "
            + "unpackHalf2x16(flw_nodes[flw_farNode + 3u].z))";

    private SundialRearReflection() {
    }

    /**
     * @param trace every captured surface behind a pixel's native one runs the reflection stage's surface
     * reflection; otherwise each rough reflection is filtered over neighbouring surfaces on its plane.
     * @param reversed the pack's sources are spelled in raw reversed depth.
     */
    static String pass(String resolve, String vertex, String reflectionStage, boolean trace, boolean reversed) {
        return transform(DeferredLodDepth.sundial(resolve, reversed), (tree, root, parser) -> {
            var main = function(root, "main");
            RearReflectionCompute.fragmentPosition(tree, root, parser);
            inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl")
                    + DeferredOitProfile.resource("layer_light.glsl"), ASTInjectionPoint.BEFORE_DECLARATIONS);
            ShaderDeclarations.uniform(tree, root, parser, "vec2", "screenSize");
            tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "vec2 _flw_farTexcoord;", "vec4 _flw_farFragCoord;");
            SundialDeferredPatch.decode(tree, root, parser);
            if (trace) mirror(tree, root, parser, main, reflectionStage, reversed);
            main.detachAndDelete();
            if (trace) RearReflectionCompute.vertexInputs(tree, root, parser, vertex);
            String pixelGlobals = RearReflectionCompute.pixelGlobals(tree);
            RearReflectionCompute.computeStage(tree, root, parser);
            inject(tree, parser, trace
                            ? DeferredOitProfile.resource("sundial_rear_trace.glsl").replace("_FLW_PIXEL_GLOBALS", pixelGlobals)
                                                .replace("texelFetch(depthtex0, pixel, 0).r",
                                                        DeferredLodDepth.front(root, "pixel"))
                                                .replace("texelFetch(depthtex1, pixel, 0).r",
                                                        DeferredLodDepth.opaque(root, "pixel"))
                            : RearReflectionCompute.filter("flw_heads", SLOT,
                                                           "screenToViewPos((vec2(pixel) + 0.5) / screenSize, uintBitsToFloat(flw_nodes[node].y))",
                                                           "normalize(_flw_farGbuffer(pixel, (vec2(pixel) + 0.5) / screenSize, node, 1.0).geoNormal)",
                                                           DeferredLodDepth.opaque(root, "pixel"))
                                                   .replace("texelFetch(depthtex0, pixel, 0).x",
                                                           DeferredLodDepth.front(root, "pixel"))
                                                   .replace("texelFetch(depthtex0, neighbour, 0).x",
                                                           DeferredLodDepth.front(root, "neighbour")),
                    ASTInjectionPoint.END);
        });
    }

    /**
     * {@code _flw_rearMirror}: the resolve body for one rear surface up to its reflection inputs, then the reflection
     * stage's surface reflection over a black background.
     */
    private static void mirror(TranslationUnit tree, Root root, ASTParser parser, FunctionDefinition main,
                               String reflectionStage, boolean reversed) {
        var reflection = SundialDeferredPatch.reflection(tree, root, parser, reflectionStage);
        if (reflection == null) throw unsupported("Sundial reflection stage has no surface reflection");
        tree.parseAndInjectNode(parser, ASTInjectionPoint.END, "void _flw_rearMirror(uint flw_farNode, ivec2 texel, "
                + "vec2 texcoord, float flw_farBack, out vec3 flw_mirrorLight, out vec4 flw_reflection, "
                + "out vec3 flw_mirrorWeight) {}");
        var mirror = (FunctionDefinition) tree.getChildren().getLast();
        mirror.getBody().replaceByAndDelete(main.getBody().cloneInto(root));
        var statements = mirror.getBody().getStatements();
        // The water stain and everything after it shade the surface itself; the reflection inputs are final here.
        truncate(mirror, member(mirror, root, "waterStain").getAncestor(DeclarationStatement.class), true);
        statements.getFirst().detachAndDelete();
        SundialDeferredPatch.initialize(mirror, root, parser, "gbufferData",
                "_flw_farGbuffer(texel, texcoord, flw_farNode, flw_farBack)");
        SundialDeferredPatch.initialize(mirror, root, parser, "waterDepth",
                "uintBitsToFloat(flw_nodes[flw_farNode].y)");
        SundialDeferredPatch.initialize(mirror, root, parser, "solidDepth", "flw_farBack");
        SundialDeferredPatch.initialize(mirror, root, parser, "solidColor",
                "vec4(vec3(0.0), uintBitsToFloat(flw_nodes[flw_farNode + 1u].w & 0x7fffffffu))");
        var outputs = outputs(tree);
        for (var statement : root.nodeIndex.getStream(ExpressionStatement.class).toList())
            if (statement.hasAncestor(mirror) && outputs.contains(written(statement))) statement.detachAndDelete();
        int refractions = 0;
        Class<? extends BinaryExpression> refraction = reversed ? GreaterThanExpression.class
                : LessThanExpression.class;
        for (var comparison : root.nodeIndex.getStream(refraction).toList()) {
            if (!comparison.hasAncestor(mirror)
                    || !ASTPrinter.printSimple(comparison.getLeft()).strip().equals("waterDepth")
                    || !ASTPrinter.printSimple(comparison.getRight()).strip().equals("targetSolidDepth")) continue;
            comparison.replaceByAndDelete(parser.parseExpression(root, "false"));
            refractions++;
        }
        if (refractions != 1) throw unsupported("rear refraction");
        statements.addAll(0, List.of(parser.parseStatement(root, "_flw_farTexcoord = texcoord;"),
                parser.parseStatement(root, "_flw_farFragCoord = vec4(vec2(texel) + 0.5, 0.0, 1.0);")));
        statements.addAll(List.of(
                parser.parseStatement(root,
                        "gbufferData = _flw_farGbuffer(texel, texcoord, flw_farNode, flw_farBack);"),
                parser.parseStatement(root, "gbufferData.depth = waterDepth;"),
                parser.parseStatement(root, "vec3 mirrorColor = vec3(0.0);"),
                parser.parseStatement(root, "vec3 mirrorWeight = vec3(solidColor.w);"),
                parser.parseStatement(root, "vec4 waterReflectionColor = vec4(0.0);"),
                parser.parseStatement(root, "float reflectionDepth = 0.0;"),
                reflection,
                parser.parseStatement(root, "flw_mirrorLight = mirrorColor;"),
                parser.parseStatement(root, "flw_reflection = waterReflectionColor;"),
                parser.parseStatement(root, "flw_mirrorWeight = mirrorWeight;")));
    }
}
