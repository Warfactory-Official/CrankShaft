package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.statement.loop.ForLoopStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.LinkedHashSet;
import java.util.Set;

final class SeusDeferredPatch {
    private SeusDeferredPatch() { }

    static String capture(String source, boolean motion, boolean opaqueReady) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            if (!root.identifierIndex.has("OutputGBufferDataTransparent"))
                throw new UnsupportedOperationException("SEUS transparent encoder is missing");
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename("main", "_flw_seusCaptureMain");
            if (!root.identifierIndex.has("screenSize"))
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform vec2 screenSize;");
            if (!root.identifierIndex.has("depthtex1"))
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform sampler2D depthtex1;");
            inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl"), ASTInjectionPoint.BEFORE_DECLARATIONS);
            inject(tree, parser, DeferredOitProfile.resource("seus_capture.glsl")
                    .replace("_FLW_SEUS_OPAQUE_READY", Boolean.toString(opaqueReady)), ASTInjectionPoint.END);
            DeferredCaptureAllocator.coalesce(tree, parser);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { _flw_seusCaptureMain(); if (gl_HelperInvocation) return;"
                            + " flw_captureSeus(gl_FragData[0], gl_FragData[1], "
                            + (motion ? "gl_FragData[2].xyz" : "vec3(0.0)") + "); }");
        });
        return parser.transform(source);
    }

    static String material(String source, int stage) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            tree.getVersionStatement().version = Version.fromNumber(430);
            tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "uniform int flw_oitFarLayer;", "uniform bool flw_oitReplaying;",
                    "layout(r32ui, binding = 7) uniform readonly uimage2D flw_layerNode;");
            tree.getOneMainDefinitionBody().getStatements().addFirst(parser.parseStatement(root,
                    "if (flw_oitReplaying && flw_oitFarLayer != 0"
                            + " && imageLoad(flw_layerNode, ivec2(gl_FragCoord.xy)).r == 0u) discard;"));
            if (stage == 4) {
                var body = tree.getOneMainDefinitionBody();
                // Camera-to-surface fog/shafts run once; reflection rays and inter-surface absorption remain authored.
                var shafts = new LinkedHashSet<ForLoopStatement>();
                for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                    if (call.getFunctionName() == null || !call.hasAncestor(body)) continue;
                    String name = call.getFunctionName().getName();
                    if (name.equals("WorldPosToShadowProjPos")) {
                        var loop = call.getAncestor(ForLoopStatement.class);
                        if (loop != null && loop.hasAncestor(body)) shafts.add(loop);
                    }
                    if (!Set.of("LandAtmosphericScattering", "UnderwaterFog", "UnderLavaFog").contains(name)) continue;
                    var statement = call.getAncestor(ExpressionStatement.class);
                    if (statement != null && statement.getExpression() == call)
                        statement.replaceByAndDelete(parser.parseStatement(root,
                                "if (flw_oitFarLayer == 0) {" + ASTPrinter.printSimple(statement) + "}"));
                }
                for (var loop : shafts)
                    loop.replaceByAndDelete(parser.parseStatement(root,
                            "if (flw_oitFarLayer == 0) {" + ASTPrinter.printSimple(loop) + "}"));
            }
        });
        return parser.transform(source);
    }

    private static void inject(TranslationUnit tree, ASTParser parser, String source, ASTInjectionPoint point) {
        var library = parser.parseTranslationUnit(tree.getRoot(), "#version 430 compatibility\n" + source);
        tree.injectNodes(point, library.getChildren().stream().map(node -> node.cloneInto(tree.getRoot())).toList());
        library.detachAndDelete();
    }
}
