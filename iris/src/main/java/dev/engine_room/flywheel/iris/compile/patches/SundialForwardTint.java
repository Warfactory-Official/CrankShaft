package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

/**
 * Imports the native translucent filter without reproducing the pack's material model.
 */
final class SundialForwardTint {
    private static final String FUNCTION = "_flw_forwardSundialGlassTransmission";

    private SundialForwardTint() {
    }

    static void inject(TranslationUnit target, Root root, ASTParser parser, String source) {
        var nativeParser = GuestShaders.versionedTransformer();
        nativeParser.setTransformation((library, nativeRoot) -> {
            var main = library.getOneMainDefinitionBody();
            var tint = declaration(nativeRoot, main, "waterStain");
            var scope = tint.getAncestor(CompoundStatement.class);
            var nativeStatements = scope.getStatements();
            var first = tint.getAncestor(DeclarationStatement.class);
            var last = nativeStatements.stream().filter(ExpressionStatement.class::isInstance)
                                       .map(ExpressionStatement.class::cast)
                                       .filter(statement -> references(nativeRoot, statement, "stainedColor")
                                               && references(nativeRoot, statement, "exp2"))
                                       .findFirst().orElseThrow();
            var initialTint = nativeRoot.nodeIndex.getStream(ExpressionStatement.class)
                                                  .filter(statement -> statement.hasAncestor(main))
                                                  .filter(statement -> references(nativeRoot, statement, "stainedColor")
                                                          && references(nativeRoot, statement, "smoothness"))
                                                  .findFirst().orElseThrow();

            target.parseAndInjectNode(parser, ASTInjectionPoint.END, """
                    vec3 _flw_forwardSundialGlassTransmission(GbufferData gbufferData, vec3 position, out vec3 reflectionWeight) {
                        gbufferData.albedo.rgb = _flw_forwardLinear(gbufferData.albedo.rgb);
                        vec3 worldNormal = normalize(mat3(gbufferModelViewInverse) * gbufferData.normal);
                        float LdotH = clamp(dot(worldNormal, _flw_forwardView(position)), 0.0, 1.0);
                        bool isTargetWater = false;
                        return stainedColor;
                    }
                    """);
            var helper = root.nodeIndex.getStream(FunctionDefinition.class)
                                       .filter(function -> function.getFunctionPrototype().getName().getName()
                                                                   .equals(FUNCTION))
                                       .findFirst().orElseThrow();
            var statements = helper.getBody().getStatements();
            statements.add(statements.size() - 1,
                    declaration(nativeRoot, main, "f0").getAncestor(DeclarationStatement.class).cloneInto(root));
            statements.add(statements.size() - 1,
                    declaration(nativeRoot, main, "stainedColor").getAncestor(DeclarationStatement.class)
                                                                 .cloneInto(root));
            statements.add(statements.size() - 1, initialTint.cloneInto(root));

            for (int i = 0; i < nativeStatements.indexOf(first); i++) {
                var statement = nativeStatements.get(i);
                if (!(statement instanceof ExpressionStatement expression)
                        || !(expression.getExpression() instanceof AssignmentExpression assignment)) continue;
                String name = ASTPrinter.printSimple(assignment.getLeft()).strip();
                if (name.equals("f0") || name.equals("gbufferData.metalness"))
                    statements.add(statements.size() - 1, statement.cloneInto(root));
            }
            for (int i = nativeStatements.indexOf(first); i <= nativeStatements.indexOf(last); i++) {
                var statement = nativeStatements.get(i);
                statements.add(statements.size() - 1, statement.cloneInto(root));
                if (references(nativeRoot, statement, "fresnel")) statements.add(statements.size() - 1,
                        parser.parseStatement(root, "reflectionWeight = vec3(1.0) - exp2(stainedColor);"));
            }

            var caller = root.nodeIndex.getStream(FunctionDefinition.class)
                                       .filter(function -> function.getFunctionPrototype().getName().getName()
                                                                   .equals("_flw_forwardSundial"))
                                       .findFirst().orElseThrow();
            var moved = helper.cloneInto(root);
            helper.detachAndDelete();
            target.getChildren().add(target.getChildren().indexOf(caller), moved);
        });
        nativeParser.transform(source);
    }

    private static TypeAndInitDeclaration declaration(Root root, ASTNode main, String name) {
        return root.nodeIndex.getStream(TypeAndInitDeclaration.class)
                             .filter(value -> value.hasAncestor(main))
                             .filter(value -> value.getMembers().stream()
                                                   .anyMatch(member -> member.getName().getName().equals(name)))
                             .findFirst().orElseThrow();
    }

    private static boolean references(Root root, ASTNode node, String name) {
        return root.identifierIndex.getStream(name).anyMatch(identifier -> identifier.hasAncestor(node));
    }
}
