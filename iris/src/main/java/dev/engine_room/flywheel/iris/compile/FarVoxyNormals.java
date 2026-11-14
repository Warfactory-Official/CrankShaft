package dev.engine_room.flywheel.iris.compile;

import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import io.github.douira.glsl_transformer.ast.traversal.ASTBaseVisitor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps captured normals when a Voxy callback derives its geometry normal from its six face values.
 */
final class FarVoxyNormals {
    private FarVoxyNormals() {
    }

    static String patch(String source) {
        var parser = new SingleASTTransformer<JobParameters>();
        var tree = parser.parseSeparateTranslationUnit("#version 460 core\n" + source);
        var root = tree.getRoot();
        var reachable = reachable(root);
        Set<String> faceNames = new HashSet<>();
        var members = root.nodeIndex.getStream(DeclarationMember.class)
                                    .filter(member -> !member.hasAncestor(FunctionDefinition.class)
                                            || reachable.stream().anyMatch(member::hasAncestor)).toList();
        for (var member : members) {
            var type = member.getAncestor(TypeAndInitDeclaration.class);
            if (Set.of("uint", "int", "float").contains(
                    ASTPrinter.printSimple(type.getType().getTypeSpecifier()).strip())
                    && member.getInitializer() instanceof ExpressionInitializer initializer
                    && readsFace(initializer, faceNames)) {
                faceNames.add(member.getName().getName());
            }
        }
        boolean adapted = false;
        for (var member : members) {
            String name = member.getName().getName();
            if (!Set.of("worldNormal", "vertexNormal", "normal", "N").contains(name)
                    || !ASTPrinter.printSimple(member.getAncestor(TypeAndInitDeclaration.class)
                                                     .getType().getTypeSpecifier()).strip().equals("vec3")) continue;
            boolean global = !member.hasAncestor(FunctionDefinition.class);
            var scopes = global ? reachable.stream().map(FunctionDefinition::getBody).toList()
                    : List.of(member.getAncestor(CompoundStatement.class));
            boolean constructed = false;
            for (var scope : scopes) {
                var statements = scope.getStatements();
                int declaration = global ? -1 : statements.indexOf(member.getAncestor(Statement.class));
                int lastFaceNormal = -1;
                String modelView = null;
                for (int i = declaration + 1; i < statements.size(); i++) {
                    Statement statement = statements.get(i);
                    if (!readsFace(statement, faceNames) || !writesNormal(statement, name)) continue;
                    lastFaceNormal = i;
                    if (references(statement, "vxModelView")) modelView = "vxModelView";
                    else if (references(statement, "gbufferModelView")) modelView = "gbufferModelView";
                }
                if (lastFaceNormal >= 0) {
                    String normal = modelView == null ? "_flw_farWorldNormal"
                            : "mat3(" + modelView + ") * _flw_farWorldNormal";
                    statements.add(lastFaceNormal + 1,
                            parser.parseStatement(root, name + " = " + normal + ";"));
                    constructed = adapted = true;
                }
            }
            if (!constructed && member.getInitializer() instanceof ExpressionInitializer initializer
                    && readsFace(initializer, faceNames)) {
                initializer.setExpression(parser.parseExpression(root, "_flw_farWorldNormal"));
                adapted = true;
            }
        }
        if (!adapted) {
            throw new IllegalStateException("Voxy far visuals require a supported geometry-normal expression");
        }
        root.replaceReferenceExpressions(parser, "gl_FragCoord", "_flw_farFragCoord");
        return tree.getChildren().stream().map(ASTPrinter::printSimple).collect(Collectors.joining("\n"));
    }

    static Set<FunctionDefinition> reachable(Root root) {
        return reachable(root, "voxy_emitFragment");
    }

    static Set<FunctionDefinition> reachable(Root root, String entry) {
        var functions = root.nodeIndex.getStream(FunctionDefinition.class).toList();
        var byName = functions.stream().collect(Collectors.groupingBy(
                candidate -> candidate.getFunctionPrototype().getName().getName()));
        var calls = root.nodeIndex.getStream(FunctionCallExpression.class).toList();
        Set<FunctionDefinition> reachable = new HashSet<>();
        var queued = new ArrayList<>(byName.get(entry));
        for (int i = 0; i < queued.size(); i++) {
            var caller = queued.get(i);
            if (!reachable.add(caller)) continue;
            for (var call : calls) {
                if (call.hasAncestor(caller) && call.getFunctionName() != null) {
                    queued.addAll(byName.getOrDefault(call.getFunctionName().getName(), List.of()));
                }
            }
        }
        return reachable;
    }

    /**
     * A DH fragment may reconstruct a flat face normal from position derivatives instead of receiving one.
     */
    static void patchDhGeometry(SingleASTTransformer<JobParameters> parser, TranslationUnit tree) {
        var root = tree.getRoot();
        var derivatives = new HashSet<String>();
        var members = root.nodeIndex.getStream(DeclarationMember.class).toList();
        for (var member : members) {
            if (!(member.getInitializer() instanceof ExpressionInitializer initializer)) continue;
            for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (!call.hasAncestor(initializer) || call.getFunctionName() == null
                        || !Set.of("dFdx", "dFdy").contains(call.getFunctionName().getName())) continue;
                if (Set.of("viewPos", "viewPosition", "positionView").stream()
                       .anyMatch(name -> references(call, name))) {
                    derivatives.add(member.getName().getName());
                }
            }
        }
        for (var member : members) {
            if (!(member.getInitializer() instanceof ExpressionInitializer initializer)
                    || !ASTPrinter.printSimple(member.getAncestor(TypeAndInitDeclaration.class)
                                                     .getType().getTypeSpecifier()).strip().equals("vec3")) continue;
            boolean cross = root.nodeIndex.getStream(FunctionCallExpression.class).anyMatch(call ->
                    call.hasAncestor(initializer) && call.getFunctionName() != null
                            && call.getFunctionName().getName().equals("cross")
                            && derivatives.stream().anyMatch(name -> references(call, name)));
            if (!cross) continue;
            String name = member.getName().getName();
            var scope = member.getAncestor(CompoundStatement.class);
            var statements = scope.getStatements();
            int lastWrite = statements.indexOf(member.getAncestor(Statement.class));
            for (int i = lastWrite + 1; i < statements.size(); i++) {
                var statement = statements.get(i);
                if (writesNormal(statement, name)) lastWrite = i;
                else if (references(statement, name)) break;
            }
            statements.add(lastWrite + 1, parser.parseStatement(root,
                    name + " = normalize(mat3(iris_ModelViewMatrix) * _flw_staticNormal);"));
        }
    }

    private static boolean readsFace(ASTNode node, Set<String> names) {
        boolean[] found = {false};
        new ASTBaseVisitor<Void>() {
            @Override
            public Void visitMemberAccessExpression(MemberAccessExpression member) {
                if (member.getMember().getName().equals("face")) found[0] = true;
                return super.visitMemberAccessExpression(member);
            }

            @Override
            public Void visitReferenceExpression(ReferenceExpression reference) {
                if (names.contains(reference.getIdentifier().getName())) found[0] = true;
                return null;
            }
        }.visit(node);
        return found[0];
    }

    private static boolean references(ASTNode node, String name) {
        boolean[] found = {false};
        new ASTBaseVisitor<Void>() {
            @Override
            public Void visitReferenceExpression(ReferenceExpression reference) {
                if (reference.getIdentifier().getName().equals(name)) found[0] = true;
                return null;
            }
        }.visit(node);
        return found[0];
    }

    private static boolean writesNormal(ASTNode node, String name) {
        boolean[] found = {false};
        new ASTBaseVisitor<Void>() {
            @Override
            public Void visitBinaryExpression(BinaryExpression expression) {
                switch (expression.getExpressionType()) {
                    case ASSIGNMENT, MULTIPLICATION_ASSIGNMENT, DIVISION_ASSIGNMENT, MODULO_ASSIGNMENT,
                         ADDITION_ASSIGNMENT, SUBTRACTION_ASSIGNMENT, LEFT_SHIFT_ASSIGNMENT,
                         RIGHT_SHIFT_ASSIGNMENT, BITWISE_AND_ASSIGNMENT, BITWISE_XOR_ASSIGNMENT,
                         BITWISE_OR_ASSIGNMENT -> {
                        if (references(expression.getLeft(), name)) found[0] = true;
                    }
                    default -> {
                    }
                }
                return super.visitBinaryExpression(expression);
            }
        }.visit(node);
        return found[0];
    }
}
