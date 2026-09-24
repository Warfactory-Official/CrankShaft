package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.ArrayList;
import java.util.List;

final class SundialDeferredPatch {
    private SundialDeferredPatch() {
    }

    static String capture(String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            for (String output : List.of("gbufferData0", "gbufferData1", "gbufferData2")) {
                if (!root.identifierIndex.has(output)) throw unsupported("missing output " + output);
            }
            tree.getVersionStatement().version = Version.fromNumber(430);
            root.rename("main", "_flw_layerMain");
            inject(tree, parser, DeferredOitProfile.resource("layer_capture.glsl"), ASTInjectionPoint.END);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { _flw_layerMain(); flw_captureLayer(gbufferData0, gbufferData1, gbufferData2); }");
        });
        return parser.transform(source);
    }

    static String composite(int index, String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            tree.getVersionStatement().version = Version.fromNumber(430);
            var body = tree.getOneMainDefinitionBody();
            List<Statement> statements = body.getStatements();
            if (index == 0) {
                int first = declaration(statements, "transparentDensity");
                int last = assignment(statements, "texBuffer5");
                if (first < 0 || last <= first) throw unsupported("cloud visibility range");
                String visibility = print(statements.subList(first, last));
                var block = (CompoundStatement) parser.parseStatement(root, "{" + visibility + "}");
                int[] replaced = {0};
                block.getRoot().nodeIndex.getStream(DeclarationMember.class)
                     .filter(member -> member.getName().getName().equals("waterDepth") && member.hasAncestor(block))
                     .toList().forEach(member -> {
                         if (!(member.getInitializer() instanceof ExpressionInitializer initializer)) {
                             throw unsupported("cloud depth initializer");
                         }
                         initializer.setExpression(
                                 parser.parseExpression(root, "uintBitsToFloat(flw_nodes[flw_node * 2u].y)"));
                         replaced[0]++;
                     });
                if (replaced[0] != 1) throw unsupported("cloud layer depth input");
                String perLayer = print(block.getStatements());
                block.detachAndDelete();
                inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl"),
                        ASTInjectionPoint.BEFORE_DECLARATIONS);
                statements.add(last, parser.parseStatement(root,
                        DeferredOitProfile.resource("layer_sort.glsl").replace("_FLW_VISIBILITY_BODY", perLayer)
                                          .replace("_FLW_SORT_PIXEL", "uvec2(gl_FragCoord.xy)")));
            } else {
                tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform int flw_oitFarLayer;");
                if (index == 1) {
                    int first = declaration(statements, "absorption");
                    int last = assignment(statements, "texBuffer4");
                    if (first < 0 || last <= first) throw unsupported("front fog range");
                    guard(statements, first + 1, last, parser, root);
                } else if (index == 2) {
                    statements.addFirst(parser.parseStatement(root, """
                            if (flw_oitFarLayer != 0) {
                                ivec2 pixel = ivec2(gl_FragCoord.xy);
                                texBuffer4 = texelFetch(colortex4, pixel, 0);
                                texBuffer5 = texelFetch(colortex5, pixel, 0);
                                return;
                            }
                            """));
                } else if (index == 6) {
                    // Front layer only: guest light + weather (weather absent without SHADOW_AND_SKY).
                    int first = declaration(statements, "_flw_guestLight");
                    int last = assignment(statements, "texBuffer5");
                    if (first < 0 || last <= first) throw unsupported("guest light range");
                    guard(statements, first, last, parser, root);
                }
            }
        });
        return parser.transform(source);
    }

    private static void guard(List<Statement> statements, int first, int last, ASTParser parser, Root root) {
        List<Statement> selected = new ArrayList<>(statements.subList(first, last));
        String replacement = "if (flw_oitFarLayer == 0) {" + print(selected) + "}";
        for (Statement statement : selected) statement.detachAndDelete();
        statements.add(first, parser.parseStatement(root, replacement));
    }

    private static void inject(TranslationUnit tree, ASTParser parser, String source, ASTInjectionPoint point) {
        var library = parser.parseTranslationUnit(tree.getRoot(), "#version 430 compatibility\n" + source);
        tree.injectNodes(point, library.getChildren().stream().map(node -> node.cloneInto(tree.getRoot())).toList());
        library.detachAndDelete();
    }

    private static int declaration(List<Statement> statements, String name) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i) instanceof DeclarationStatement statement
                    && statement.getDeclaration() instanceof TypeAndInitDeclaration declaration
                    && declaration.getMembers().stream().anyMatch(member -> member.getName().getName().equals(name)))
                return i;
        }
        return -1;
    }

    private static int assignment(List<Statement> statements, String name) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i) instanceof ExpressionStatement statement
                    && statement.getExpression() instanceof AssignmentExpression assignment
                    && ASTPrinter.printSimple(assignment.getLeft()).strip().equals(name)) return i;
        }
        return -1;
    }

    private static String print(List<Statement> statements) {
        StringBuilder result = new StringBuilder();
        for (Statement statement : statements) result.append(ASTPrinter.printSimple(statement)).append('\n');
        return result.toString();
    }

    private static UnsupportedOperationException unsupported(String reason) {
        return new UnsupportedOperationException("Sundial deferred adapter: " + reason);
    }
}
