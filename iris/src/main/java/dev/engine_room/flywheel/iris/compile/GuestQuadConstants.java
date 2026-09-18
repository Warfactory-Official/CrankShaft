package dev.engine_room.flywheel.iris.compile;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionParameter;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.ArrayAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.UnaryExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.loop.LoopStatement;
import io.github.douira.glsl_transformer.ast.node.statement.selection.SelectionStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ReturnStatement;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.query.index.IdentifierIndex;
import io.github.douira.glsl_transformer.ast.query.index.SuperclassNodeIndex;
import io.github.douira.glsl_transformer.ast.traversal.ASTBaseVisitor;
import org.jspecify.annotations.Nullable;

import java.util.*;

final class GuestQuadConstants {
    private static final String UNKNOWN = "@varying";
    private static final Set<String> QUAD_INPUTS = Set.of("mc_Entity", "iris_Normal", "mc_midTexCoord");
    private static final Set<String> PURE = Set.of(
            ("radians degrees sin cos tan asin acos atan sinh cosh tanh asinh acosh atanh "
                    + "pow exp log exp2 log2 sqrt inversesqrt abs sign floor trunc round roundEven ceil fract mod min max clamp mix step smoothstep "
                    + "isnan isinf floatBitsToInt floatBitsToUint intBitsToFloat uintBitsToFloat fma ldexp "
                    + "packUnorm2x16 packSnorm2x16 packUnorm4x8 packSnorm4x8 unpackUnorm2x16 unpackSnorm2x16 unpackUnorm4x8 unpackSnorm4x8 "
                    + "packHalf2x16 unpackHalf2x16 length distance dot cross normalize faceforward reflect refract matrixCompMult outerProduct transpose inverse determinant "
                    + "lessThan lessThanEqual greaterThan greaterThanEqual equal notEqual any all not bitfieldExtract bitfieldInsert bitfieldReverse bitCount findLSB findMSB "
                    + "textureSize textureQueryLevels texture textureLod textureOffset texelFetch texelFetchOffset textureLodOffset textureProj textureProjLod").split(
                    " "));

    private final Map<String, Set<String>> dependencies = new HashMap<>();
    private final Map<String, List<FunctionDefinition>> functions = new HashMap<>();
    private final Map<String, Integer> declarations = new HashMap<>();
    private final Set<String> constants = new HashSet<>();
    private final Map<FunctionDefinition, Set<String>> exitGuards = new HashMap<>();
    private boolean packedQuadData;

    private GuestQuadConstants() {
    }

    static Set<String> find(TranslationUnit tree) {
        tree = tree.cloneInto(
                new RootSupplier(SuperclassNodeIndex::withUnordered, IdentifierIndex::withOnlyExact).get());
        int serial = 0;
        for (FunctionDefinition function : tree.getRoot().nodeIndex.getStream(FunctionDefinition.class).toList()) {
            for (FunctionParameter parameter : function.getFunctionPrototype().getParameters()) {
                if (parameter.getName() == null) continue;
                String old = parameter.getName().getName();
                String renamed = "_flw_quadArg" + serial++;
                for (var identifier : tree.getRoot().identifierIndex.getStream(old).toList()) {
                    if (identifier.getAncestor(FunctionDefinition.class) != function) continue;
                    if (identifier.getParent() instanceof ReferenceExpression
                            || identifier.getParent() instanceof DeclarationMember
                            || identifier == parameter.getName()) identifier.setName(renamed);
                }
            }
        }
        GuestQuadConstants proof = new GuestQuadConstants();
        proof.analyze(tree);
        return Set.copyOf(proof.constants);
    }

    private static String name(FunctionDefinition function) {
        return function.getFunctionPrototype().getName().getName();
    }

    private static @Nullable String target(Expression expression) {
        if (expression.getExpressionType() == Expression.ExpressionType.GROUPING)
            return target(((UnaryExpression) expression).getOperand());
        if (expression instanceof ReferenceExpression reference) return reference.getIdentifier().getName();
        if (expression instanceof MemberAccessExpression member) return target(member.getOperand());
        if (expression instanceof ArrayAccessExpression array) return target(array.getLeft());
        return null;
    }

    private static boolean storage(@Nullable TypeQualifier qualifiers, StorageQualifier.StorageType kind) {
        return qualifiers != null && qualifiers.getParts().stream()
                                               .anyMatch(
                                                       part -> part instanceof StorageQualifier storage && storage.storageType == kind);
    }

    private void analyze(TranslationUnit tree) {
        var index = tree.getRoot().nodeIndex;
        index.getStream(DeclarationMember.class).forEach(member -> declared(member.getName().getName()));
        index.getStream(FunctionParameter.class).filter(parameter -> parameter.getName() != null)
             .forEach(parameter -> declared(parameter.getName().getName()));
        packedQuadData = declarations.getOrDefault("a_LightAndData", 0) == 1;
        index.getStream(FunctionDefinition.class).forEach(function -> {
            functions.computeIfAbsent(name(function), ignored -> new ArrayList<>()).add(function);
            exitGuards.put(function, new HashSet<>());
        });
        index.getStream(ReturnStatement.class).forEach(statement -> {
            FunctionDefinition function = statement.getAncestor(FunctionDefinition.class);
            if (function != null) exitGuards.get(function).addAll(branches(statement));
        });
        dependencies.put("@call:main", new HashSet<>());
        constants.addAll(Set.of("gl_BaseVertex", "gl_BaseVertexARB", "gl_BaseInstance", "gl_BaseInstanceARB",
                "gl_InstanceID", "gl_DrawID", "gl_DrawIDARB"));
        index.getStream(TypeAndInitDeclaration.class).forEach(declaration -> {
            boolean global = declaration.getAncestor(FunctionDefinition.class) == null;
            TypeQualifier qualifiers = declaration.getType().getTypeQualifier();
            for (var member : declaration.getMembers()) {
                String name = member.getName().getName();
                if (global && (storage(qualifiers, StorageQualifier.StorageType.UNIFORM)
                        || storage(qualifiers, StorageQualifier.StorageType.IN) && QUAD_INPUTS.contains(name))) {
                    if (declarations.getOrDefault(name, 0) == 1) constants.add(name);
                } else if (member.getInitializer() != null) {
                    write(name, reads(member.getInitializer()), member);
                }
            }
        });
        index.getStream(InterfaceBlockDeclaration.class).forEach(block -> {
            if (!storage(block.getTypeQualifier(), StorageQualifier.StorageType.UNIFORM)) return;
            if (block.getVariableName() != null) {
                String name = block.getVariableName().getName();
                if (!declarations.containsKey(name)) constants.add(name);
            } else {
                for (var member : block.getStructBody().getMembers())
                    for (var item : member.getDeclarators()) {
                        String name = item.getName().getName();
                        if (!declarations.containsKey(name)) constants.add(name);
                    }
            }
        });
        index.getStream(BinaryExpression.class)
             .filter(expression -> expression.getExpressionType().name().endsWith("ASSIGNMENT"))
             .forEach(expression -> {
                 String target = target(expression.getLeft());
                 if (target != null) {
                     Set<String> values = reads(expression.getRight());
                     // Subscript selection is part of the write's dependency, even when only one component changes.
                     values.addAll(reads(expression.getLeft()));
                     values.remove(target);
                     write(target, values, expression);
                 }
             });
        index.getStream(UnaryExpression.class).filter(expression -> {
            String kind = expression.getExpressionType().name();
            return kind.startsWith("INCREMENT") || kind.startsWith("DECREMENT");
        }).forEach(expression -> {
            String target = target(expression.getOperand());
            if (target != null) write(target, Set.of(UNKNOWN), expression);
        });
        for (var entry : functions.entrySet())
            for (FunctionDefinition function : entry.getValue()) {
                add("@return:" + entry.getKey(), reads(function.getBody()));
            }
        index.getStream(FunctionCallExpression.class).filter(call -> call.getFunctionName() != null).forEach(call -> {
            String name = call.getFunctionName().getName();
            List<FunctionDefinition> candidates = functions.get(name);
            if (candidates == null) {
                if (!PURE.contains(name)) for (Expression argument : call.getParameters()) {
                    String target = target(argument);
                    if (target != null) add(target, Set.of(UNKNOWN));
                }
                return;
            }
            Set<String> controls = controls(call);
            add("@call:" + name, controls);
            for (FunctionDefinition function : candidates) {
                var parameters = function.getFunctionPrototype().getParameters();
                if (parameters.size() != call.getParameters().size()) continue;
                for (int i = 0; i < parameters.size(); i++) {
                    FunctionParameter parameter = parameters.get(i);
                    if (parameter.getName() == null) continue;
                    TypeQualifier qualifiers = parameter.getType().getTypeQualifier();
                    boolean output = storage(qualifiers, StorageQualifier.StorageType.OUT);
                    boolean inout = storage(qualifiers, StorageQualifier.StorageType.INOUT);
                    if (!output) add(parameter.getName().getName(), reads(call.getParameters().get(i)));
                    if (output || inout) {
                        String target = target(call.getParameters().get(i));
                        if (target != null) {
                            Set<String> values = reads(call.getParameters().get(i));
                            values.remove(target);
                            values.add(parameter.getName().getName());
                            write(target, values, call);
                        }
                    }
                }
            }
        });
        // A shadowed declaration is never a constant seed, even if its global namesake is a uniform.
        constants.removeIf(name -> declarations.getOrDefault(name, 0) > 1 || dependencies.getOrDefault(name, Set.of())
                                                                                         .contains(UNKNOWN));
        boolean changed;
        do {
            changed = false;
            for (var entry : dependencies.entrySet()) {
                if (!constants.contains(entry.getKey()) && constants.containsAll(entry.getValue())) {
                    constants.add(entry.getKey());
                    changed = true;
                }
            }
        } while (changed);
    }

    private void declared(String name) {
        declarations.merge(name, 1, Integer::sum);
    }

    private void write(String target, Set<String> values, ASTNode node) {
        Set<String> combined = new HashSet<>(values);
        combined.addAll(controls(node));
        add(target, combined);
    }

    private void add(String target, Set<String> values) {
        dependencies.computeIfAbsent(target, ignored -> new HashSet<>()).addAll(values);
    }

    private Set<String> controls(ASTNode node) {
        Set<String> result = branches(node);
        FunctionDefinition function = node.getAncestor(FunctionDefinition.class);
        if (function != null) {
            result.add("@call:" + name(function));
            result.addAll(exitGuards.get(function));
        }
        return result;
    }

    private Set<String> branches(ASTNode node) {
        Set<String> result = new HashSet<>();
        for (ASTNode parent = node.getParent(); parent != null && !(parent instanceof FunctionDefinition); parent = parent.getParent()) {
            if (parent instanceof SelectionStatement selection) result.addAll(reads(selection.getCondition()));
            else if (parent instanceof LoopStatement || parent.getClass().getSimpleName().equals("SwitchStatement"))
                result.add(UNKNOWN);
            else if (parent instanceof Expression expression && (expression.getExpressionType() == Expression.ExpressionType.CONDITION
                    || expression.getExpressionType() == Expression.ExpressionType.BOOLEAN_AND
                    || expression.getExpressionType() == Expression.ExpressionType.BOOLEAN_OR))
                result.addAll(reads(parent));
        }
        return result;
    }

    private Set<String> reads(ASTNode node) {
        Set<String> result = new HashSet<>();
        new ASTBaseVisitor<Void>() {
            @Override
            public Void visitReferenceExpression(ReferenceExpression reference) {
                result.add(reference.getIdentifier().getName());
                return null;
            }

            @Override
            public Void visitMemberAccessExpression(MemberAccessExpression member) {
                if (packedQuadData && member.getOperand() instanceof ReferenceExpression reference
                        && reference.getIdentifier().getName().equals("a_LightAndData")
                        && member.getMember().getName().matches("[zwba]+")) return null;
                return super.visitMemberAccessExpression(member);
            }

            @Override
            public Void visitArrayAccessExpression(ArrayAccessExpression array) {
                if (packedQuadData && array.getLeft() instanceof ReferenceExpression reference
                        && reference.getIdentifier().getName().equals("a_LightAndData")
                        && array.getRight() instanceof LiteralExpression literal
                        && (literal.getInteger() == 2 || literal.getInteger() == 3)) return null;
                return super.visitArrayAccessExpression(array);
            }

            @Override
            public Void visitFunctionCallExpression(FunctionCallExpression call) {
                if (call.getFunctionName() != null) {
                    String name = call.getFunctionName().getName();
                    if (functions.containsKey(name)) result.add("@return:" + name);
                    else if (!PURE.contains(name)) result.add(UNKNOWN);
                }
                for (var argument : call.getParameters()) visit(argument);
                return null;
            }
        }.startVisit(node);
        return result;
    }
}
