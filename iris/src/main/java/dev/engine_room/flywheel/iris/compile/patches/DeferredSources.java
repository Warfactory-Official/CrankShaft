package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructDeclarator;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructSpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AST operations shared by the inline deferred-resolve adapters. Lookups are scoped so grafted copies never alias.
 */
final class DeferredSources {
    private static final Pattern INTERFACE = Pattern.compile(
            ".*\\b(uniform|in|out|varying|attribute|buffer|shared)\\b.*");

    private DeferredSources() {
    }

    interface Transformation {
        void apply(TranslationUnit tree, Root root, ASTParser parser);
    }

    static String transform(String source, Transformation transformation) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            tree.getVersionStatement().version = Version.fromNumber(430);
            transformation.apply(tree, root, parser);
        });
        return parser.transform(source);
    }

    static void inject(TranslationUnit tree, ASTParser parser, String source, ASTInjectionPoint point) {
        var library = parser.parseTranslationUnit(tree.getRoot(), "#version 430 compatibility\n" + source);
        tree.injectNodes(point, library.getChildren().stream().map(node -> node.cloneInto(tree.getRoot())).toList());
        library.detachAndDelete();
    }

    /**
     * A capture is a storage side effect, which late depth tests do not stop: a fragment behind depth written after
     * {@code depthtex1} (a later depth-writing translucent) still records. Early tests would also write depth for
     * fragments the program discards, so {@code GuestShaders.lateCapture} drops them again per pipeline.
     */
    static void earlyFragmentTests(TranslationUnit tree, Root root, ASTParser parser) {
        if (root.identifierIndex.has("gl_FragDepth")) {
            // TODO: a native contract capture writing gl_FragDepth gets neither early tests nor the snapshot test (only
            //  guest captures test it in the shader), so its light behind later depth-writing translucents resolves.
            FlwBackend.LOGGER.debug("Light capture writes gl_FragDepth; late depth tests kept");
            return;
        }
        inject(tree, parser, "layout(early_fragment_tests) in;", ASTInjectionPoint.BEFORE_DECLARATIONS);
    }

    static int declaration(List<Statement> statements, String name) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i) instanceof DeclarationStatement statement
                    && statement.getDeclaration() instanceof TypeAndInitDeclaration declaration
                    && declaration.getMembers().stream().anyMatch(member -> member.getName().getName().equals(name)))
                return i;
        }
        return -1;
    }

    static int assignment(List<Statement> statements, String name) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i) instanceof ExpressionStatement statement
                    && statement.getExpression() instanceof AssignmentExpression assignment
                    && ASTPrinter.printSimple(assignment.getLeft()).strip().equals(name)) return i;
        }
        return -1;
    }

    static String print(List<Statement> statements) {
        StringBuilder result = new StringBuilder();
        for (Statement statement : statements) result.append(ASTPrinter.printSimple(statement)).append('\n');
        return result.toString();
    }

    static FunctionDefinition function(Root root, String name) {
        var functions = root.nodeIndex.getStream(FunctionDefinition.class)
                                      .filter(function -> function.getFunctionPrototype().getName().getName()
                                                                  .equals(name))
                                      .toList();
        if (functions.size() != 1) throw unsupported("function " + name + " x" + functions.size());
        return functions.getFirst();
    }

    static DeclarationMember member(ASTNode scope, Root root, String name) {
        var members = root.nodeIndex.getStream(DeclarationMember.class)
                                    .filter(member -> member.hasAncestor(scope) && member.getName().getName()
                                                                                         .equals(name))
                                    .toList();
        if (members.size() != 1) throw unsupported("declaration " + name + " x" + members.size());
        return members.getFirst();
    }

    static List<FunctionCallExpression> calls(ASTNode scope, Root root, String name) {
        return root.nodeIndex.getStream(FunctionCallExpression.class)
                             .filter(call -> call.hasAncestor(scope) && call.getFunctionName() != null
                                     && call.getFunctionName().getName().equals(name)).toList();
    }

    /**
     * Calls in {@code scope} whose first argument is {@code sampler}.
     */
    static List<FunctionCallExpression> samplerReads(ASTNode scope, Root root, String sampler) {
        return root.nodeIndex.getStream(FunctionCallExpression.class)
                             .filter(call -> call.hasAncestor(scope) && !call.getParameters().isEmpty()
                                     && ASTPrinter.printSimple(call.getParameters().getFirst()).strip().equals(sampler))
                             .toList();
    }

    /**
     * Names of the unit's fragment outputs.
     */
    static Set<String> outputs(TranslationUnit tree) {
        var names = new HashSet<String>();
        for (var child : tree.getChildren()) {
            if (child instanceof DeclarationExternalDeclaration external
                    && external.getDeclaration() instanceof TypeAndInitDeclaration declaration
                    && ASTPrinter.printSimple(declaration.getType()).matches(".*\\bout\\b.*"))
                declaration.getMembers().forEach(member -> names.add(member.getName().getName()));
        }
        return names;
    }

    /**
     * The root variable an assignment statement writes, or null.
     */
    static String written(Statement statement) {
        if (!(statement instanceof ExpressionStatement expression)
                || !(expression.getExpression() instanceof AssignmentExpression assignment)) return null;
        return ASTPrinter.printSimple(assignment.getLeft()).strip().split("[.\\[]")[0];
    }

    /**
     * Defines {@code name} at the end of the unit with a copy of {@code source}'s body, its parameters followed by
     * {@code extra}.
     */
    static FunctionDefinition graft(TranslationUnit tree, Root root, ASTParser parser, FunctionDefinition source,
                                    String returnType, String name, String extra) {
        var prototype = source.getFunctionPrototype();
        String parameters = prototype.getParameters().stream().map(ASTPrinter::printSimple).map(String::strip)
                                     .collect(Collectors.joining(", "));
        if (!extra.isEmpty()) parameters = parameters.isEmpty() ? extra : parameters + ", " + extra;
        var body = source.getBody().cloneInto(root);
        tree.parseAndInjectNode(parser, ASTInjectionPoint.END, returnType + ' ' + name + '(' + parameters + ") {}");
        var copy = (FunctionDefinition) tree.getChildren().getLast();
        copy.getBody().replaceByAndDelete(body);
        return copy;
    }

    /**
     * Deletes every statement of {@code scope} that runs after {@code statement} (and {@code statement} itself when
     * {@code inclusive}), at each nesting level up to the function body.
     */
    static void truncate(FunctionDefinition scope, Statement statement, boolean inclusive) {
        for (ASTNode node = statement, parent; node != scope.getBody(); node = parent) {
            parent = node.getParent();
            if (!(parent instanceof CompoundStatement block)) continue;
            var siblings = block.getStatements();
            int first = siblings.indexOf((Statement) node) + (inclusive && node == statement ? 0 : 1);
            List.copyOf(siblings.subList(first, siblings.size())).forEach(Statement::detachAndDelete);
        }
    }

    /**
     * Declares {@code function} immediately before {@code user}, after every type its prototype names.
     */
    static void declareBefore(TranslationUnit tree, ASTParser parser, FunctionDefinition function,
                              FunctionDefinition user) {
        var declaration = parser.parseExternalDeclaration(tree.getRoot(),
                ASTPrinter.printSimple(function.getFunctionPrototype()) + ';');
        tree.getChildren().add(tree.getChildren().indexOf(user), declaration);
    }

    /**
     * Replaces every call in {@code scope} whose first argument names a mapped sampler; returns the count.
     */
    static int redirect(ASTNode scope, Root root, ASTParser parser, Map<String, String> reads) {
        int replaced = 0;
        for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
            if (!call.hasAncestor(scope) || call.getParameters().isEmpty()) continue;
            String value = reads.get(ASTPrinter.printSimple(call.getParameters().getFirst()).strip());
            if (value == null) continue;
            call.replaceByAndDelete(parser.parseExpression(root, value));
            replaced++;
        }
        return replaced;
    }

    /**
     * Renames to {@code prefix + name} the unit's own functions (except main), structs and non-interface globals.
     */
    static Map<String, String> namespace(TranslationUnit unit, String prefix) {
        var renames = new HashMap<String, String>();
        for (var child : unit.getChildren()) {
            if (child instanceof FunctionDefinition function) {
                String name = function.getFunctionPrototype().getName().getName();
                if (!name.equals("main")) renames.put(name, prefix + name);
            } else if (child instanceof DeclarationExternalDeclaration external
                    && external.getDeclaration() instanceof TypeAndInitDeclaration declaration) {
                if (declaration.getType()
                               .getTypeSpecifier() instanceof StructSpecifier struct && struct.getName() != null)
                    renames.put(struct.getName().getName(), prefix + struct.getName().getName());
                if (!INTERFACE.matcher(ASTPrinter.printSimple(declaration.getType())).matches())
                    declaration.getMembers().forEach(member -> renames.put(member.getName().getName(),
                            prefix + member.getName().getName()));
            }
        }
        // Member selections and struct fields share the identifier index but not the namespace.
        renames.forEach((name, renamed) -> {
            for (var identifier : unit.getRoot().identifierIndex.getStream(name).toList()) {
                if (identifier.getParent() instanceof MemberAccessExpression access && access.getMember() == identifier
                        || identifier.getParent() instanceof StructDeclarator) continue;
                identifier.setName(renamed);
            }
        });
        return renames;
    }

    /**
     * Declares the rear-surface walker over {@code color} backgrounds and per-pixel {@code heads} lists, and defines
     * it after every grafted body. {@code viewPosition} maps {@code coord, depth} to a view-space position;
     * {@code opaque} reads the depth of the pack's opaque surface at {@code pixel}.
     */
    static void walker(TranslationUnit tree, ASTParser parser, String color, String viewPosition, String heads,
                       String opaque) {
        tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "float flw_farDepth(ivec2 pixel, float front, vec4 plane, bool planar, float opaque);",
                color + " flw_farComposite(ivec2 pixel, float front, vec4 plane, bool planar, " + color + " color);",
                "uint flw_farHead(ivec2 pixel);");
        inject(tree, parser, DeferredOitProfile.resource("layer_deferred.glsl").replace("_FLW_FAR_COLOR", color)
                                               .replace("_FLW_FAR_VIEW_POSITION", viewPosition)
                                               .replace("_FLW_FAR_HEADS", heads)
                                               .replace("_FLW_FAR_OPAQUE", opaque), ASTInjectionPoint.END);
    }

    /**
     * Declares {@code flw_farPath} over the {@link #walker}: rear surfaces refract along the path the adapter's
     * {@code _flw_farRefraction} defines. {@code opaque} and {@code opaqueColor} read the pack's opaque surface at
     * {@code pixel}.
     */
    static void pathWalker(TranslationUnit tree, ASTParser parser, String color, String opaque, String opaqueColor) {
        tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                color + " flw_farPath(ivec2 pixel, float front, vec4 plane, bool planar, " + color + " background);");
        inject(tree, parser, DeferredOitProfile.resource("layer_deferred_path.glsl").replace("_FLW_FAR_COLOR", color)
                                               .replace("_FLW_FAR_OPAQUE_COLOR", opaqueColor)
                                               .replace("_FLW_FAR_OPAQUE", opaque), ASTInjectionPoint.END);
    }

    static UnsupportedOperationException unsupported(String reason) {
        return new UnsupportedOperationException("Deferred resolve: " + reason);
    }
}
