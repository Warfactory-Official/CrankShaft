package dev.engine_room.flywheel.iris.compile.patches;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DiscardStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.Map;

import static dev.engine_room.flywheel.iris.compile.patches.DeferredSources.*;

/**
 * Rear-surface reflections for IterationRP, ahead of the translucent composition: compute passes built from the pack's
 * specular tracing pass. Material records carry the reflection in their fourth slot.
 */
final class IterationRearReflection {
    static final String SLOT = "3u";

    private IterationRearReflection() {
    }

    /**
     * {@code trace}: every captured surface behind a pixel's native one traces its reflection with the specular
     * pass's body; otherwise each trace is filtered over neighbouring surfaces on its plane. {@code fragment}/
     * {@code vertex}: the specular pass. {@code reversed}: the pack's sources are spelled in raw reversed depth.
     */
    static String pass(String fragment, String vertex, boolean trace, boolean reversed) {
        return transform(fragment, (tree, root, parser) -> {
            RearReflectionCompute.fragmentPosition(tree, root, parser);
            ShaderDeclarations.uniform(tree, root, parser, "vec2", "screenSize");
            ShaderDeclarations.uniform(tree, root, parser, "sampler2D", "pixelData2D");
            inject(tree, parser, DeferredOitProfile.resource("iteration_layers.glsl")
                            + DeferredOitProfile.resource("iteration_materials.glsl")
                            + DeferredOitProfile.resource("iteration_light_resolve.glsl"),
                    ASTInjectionPoint.BEFORE_DECLARATIONS);
            var decode = graft(tree, root, parser, function(root, "GetGbufferDataTranslucent"), "GbufferData",
                    "_flw_rearGetGbufferDataTranslucent", "uint flw_farNode");
            if (redirect(decode, root, parser, Map.of("colortex0", IterationDeferredPatch.ALBEDO,
                    "colortex3", IterationDeferredPatch.DATA, "colortex4", IterationDeferredPatch.NORMAL)) < 3)
                throw unsupported("Iteration specular decode reads");
            var lod = IterationDeferredPatch.lodRemap(function(root, "main"), root, "depth", reversed);
            String front = "texelFetch(depthtex0, pixel, 0).x";
            if (lod != null) {
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                        IterationDeferredPatch.LOD_BOUND_PROTOTYPE);
                tree.parseAndInjectNode(parser, ASTInjectionPoint.END, IterationDeferredPatch.LOD_BOUND);
                front = "flw_lodBound(" + front + ", texelFetch(" + lod.sampler() + ", pixel, 0).x)";
            }
            specular(tree, root, parser);
            if (trace) RearReflectionCompute.vertexInputs(tree, root, parser, vertex);
            String pixelGlobals = RearReflectionCompute.pixelGlobals(tree);
            RearReflectionCompute.computeStage(tree, root, parser);
            inject(tree, parser, trace
                            ? DeferredOitProfile.resource("iteration_rear_trace.glsl")
                                                .replace("_FLW_PIXEL_GLOBALS", pixelGlobals)
                                                .replace("texelFetch(depthtex0, pixel, 0).x", front)
                            : RearReflectionCompute.filter("flw_materialHeads", SLOT,
                                                           "ViewPos_From_ScreenPos((vec2(pixel) + 0.5) / screenSize, uintBitsToFloat(flw_nodes[node].y))",
                                                           "normalize(mat3(gbufferModelView) * DecodeNormal(unpackUnorm2x16(flw_nodes[node + 1u].w)))",
                                                           "(_FLW_REVERSED_DEPTH ? -1.0 : 2.0)").replace("texelFetch(depthtex0, pixel, 0).x", front)
                                                   .replace("texelFetch(depthtex0, neighbour, 0).x",
                                                           front.replace("pixel", "neighbour")),
                    ASTInjectionPoint.END);
        });
    }

    /**
     * The pass body as {@code vec4 _flw_rearSpecular(uint flw_farNode)}: its output, without history stores.
     */
    private static void specular(TranslationUnit tree, Root root, ASTParser parser) {
        var main = function(root, "main");
        var outputs = outputs(tree);
        if (outputs.size() != 1) throw unsupported("Iteration specular outputs");
        String output = outputs.iterator().next();
        for (var identifier : root.identifierIndex.getStream(output).toList())
            if (identifier.hasAncestor(main)) identifier.setName("flw_specular");
        for (var call : calls(main, root, "imageStore")) {
            var statement = call.getAncestor(ExpressionStatement.class);
            if (statement == null || statement.getExpression() != call)
                throw unsupported("Iteration specular image store");
            statement.detachAndDelete();
        }
        var decodes = calls(main, root, "GetGbufferDataTranslucent");
        if (decodes.size() != 1) throw unsupported("Iteration specular decode");
        decodes.getFirst().replaceByAndDelete(parser.parseExpression(root, "_flw_rearGetGbufferDataTranslucent("
                + String.join(", ", decodes.getFirst().getParameters().stream().map(ASTPrinter::printSimple).toList())
                + ", flw_farNode)"));
        redirect(main, root, parser, Map.of("depthtex0", "vec4(" + IterationDeferredPatch.NODE_DEPTH + ")"));
        for (var discard : root.nodeIndex.getStream(DiscardStatement.class).toList())
            if (discard.hasAncestor(main)) discard.replaceByAndDelete(parser.parseStatement(root, "return vec4(0.0);"));
        var specular = graft(tree, root, parser, main, "vec4", "_flw_rearSpecular", "uint flw_farNode");
        var statements = specular.getBody().getStatements();
        statements.addFirst(parser.parseStatement(root, "vec4 flw_specular = vec4(0.0);"));
        statements.add(parser.parseStatement(root, "return flw_specular;"));
        main.detachAndDelete();
    }
}
