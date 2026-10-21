package dev.engine_room.flywheel.backend.engine.shaderpack.shader;

import io.github.douira.glsl_transformer.GLSLLexer;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExtensionDirective;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.FunctionPrototype;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.query.match.Matcher;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.token_filter.TokenChannel;
import org.antlr.v4.runtime.Token;
import java.util.function.Predicate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class GlslAst {
    private static final ASTParser PARSER = new ASTParser();
    public final TranslationUnit tree;
    private String comments;

    private GlslAst(String source) {
        var lexer = GlslSourceLexer.create(source);
        var version = Version.latest;
        var versionValue = false;
        StringBuilder comments = null;
        for (var token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            if (token.getType() == GLSLLexer.NR_VERSION) versionValue = true;
            else if (versionValue && token.getChannel() == TokenChannel.DEFAULT.ordinal()) {
                version = Version.fromToken(token);
                versionValue = false;
            }
            if (token.getChannel() == TokenChannel.COMMENTS.ordinal()) {
                if (comments == null) comments = new StringBuilder();
                comments.append(source, token.getStartIndex(), token.getStopIndex() + 1).append('\n');
            }
        }
        // Legacy packs may use identifiers that became keywords in newer GLSL versions.
        PARSER.getLexer().version = version;
        tree = PARSER.parseTranslationUnit(RootSupplier.DEFAULT, source);
        this.comments = comments == null ? "" : comments.toString();
    }

    public static GlslAst parse(String source) { return preprocessed(GlslPreprocessor.preprocess(source)); }

    public static GlslAst preprocessed(String source) { return new GlslAst(source); }

    public <N extends ASTNode> Stream<N> nodes(Class<N> type) { return tree.getRoot().nodeIndex.getStream(type); }

    public Stream<TypeAndInitDeclaration> globals() {
        return tree.getChildren().stream().filter(DeclarationExternalDeclaration.class::isInstance)
                .map(DeclarationExternalDeclaration.class::cast).map(DeclarationExternalDeclaration::getDeclaration)
                .filter(TypeAndInitDeclaration.class::isInstance).map(TypeAndInitDeclaration.class::cast);
    }

    public boolean has(String name) { return tree.getRoot().identifierIndex.getStream(name).findAny().isPresent(); }

    public boolean references(String name) {
        return tree.getRoot().identifierIndex.getStream(name)
                .anyMatch(identifier -> identifier.getParent() instanceof ReferenceExpression);
    }

    public FunctionDefinition function(String name) {
        FunctionDefinition found = null;
        for (var function : tree.getRoot().nodeIndex.get(FunctionDefinition.class)) {
            if (!function.getFunctionPrototype().getName().getName().equals(name)) continue;
            if (found != null) throw new UnsupportedOperationException("Expected one GLSL function: " + name);
            found = function;
        }
        if (found == null) throw new UnsupportedOperationException("Expected one GLSL function: " + name);
        return found;
    }

    public Stream<FunctionCallExpression> calls(String name) {
        return nodes(FunctionCallExpression.class).filter(call -> call.getFunctionName() != null
                && call.getFunctionName().getName().equals(name));
    }

    public List<Assignment> assignments(String left) {
        var matcher = new Matcher<>(PARSER.parseExpression(RootSupplier.DEFAULT, left));
        return Stream.concat(nodes(AssignmentExpression.class).filter(value -> matcher.matches(value.getLeft())).map(Assignment::new),
                nodes(DeclarationMember.class).filter(member -> member.getName().getName().equals(left)
                        && member.getInitializer() instanceof ExpressionInitializer).map(Assignment::new)).toList();
    }

    public boolean within(ASTNode node, String name) {
        return tree.getRoot().identifierIndex.getStream(name).anyMatch(identifier -> identifier.hasAncestor(node));
    }

    public void renameFunction(String from, String to) {
        // Renaming identifiers does not mutate the node-type indexes used for these traversals.
        for (var function : tree.getRoot().nodeIndex.get(FunctionPrototype.class))
            if (function.getName().getName().equals(from)) function.getName().setName(to);
        for (var call : tree.getRoot().nodeIndex.get(FunctionCallExpression.class)) {
            var name = call.getFunctionName();
            if (name != null && name.getName().equals(from)) name.setName(to);
        }
    }

    public void rename(String from, String to) { tree.getRoot().rename(from, to); }

    public void references(Map<String, String> replacements) {
        replacements.forEach((name, expression) -> tree.getRoot().replaceReferenceExpressions(PARSER, name, expression));
    }

    public void replaceExpression(String pattern, String replacement, boolean required) {
        var found = expressions(pattern);
        if (required && found.isEmpty()) throw new UnsupportedOperationException("GLSL expression contract changed: " + pattern);
        found.forEach(node -> node.replaceByAndDelete(expression(replacement)));
    }

    public List<? extends Expression> expressions(String pattern) {
        var matcher = new Matcher<>(PARSER.parseExpression(RootSupplier.DEFAULT, pattern));
        return nodes(matcher.getPatternClass()).filter(matcher::matches).toList();
    }

    public Expression expression(String source) { return PARSER.parseExpression(tree.getRoot(), source); }

    public ExternalDeclaration declaration(String source) { return PARSER.parseExternalDeclaration(tree.getRoot(), source); }

    public Statement statement(String source) { return PARSER.parseStatement(tree.getRoot(), source); }

    private void insertStatements(CompoundStatement target, int index, String source) {
        var block = (CompoundStatement) PARSER.parseStatement(tree.getRoot(), "{" + source + "}");
        var statements = block.getStatements();
        while (!statements.isEmpty()) {
            var node = statements.getFirst();
            // Detach first: glsl-transformer keeps lastParent when it adopts a still-parented node.
            node.detach();
            target.getStatements().add(index++, node);
        }
        block.unregisterSubtree();
    }

    public void prependMain(String source) { insertStatements(function("main").getBody(), 0, source); }

    public void appendMain(String source) {
        var body = function("main").getBody();
        insertStatements(body, body.getStatements().size(), source);
    }

    public void insertBefore(Statement anchor, String source) { insert(anchor, source, false); }

    public void insertAfter(Statement anchor, String source) { insert(anchor, source, true); }

    private void insert(Statement anchor, String source, boolean after) {
        if (anchor.getParent() instanceof CompoundStatement block) {
            insertStatements(block, block.getStatements().indexOf(anchor) + (after ? 1 : 0), source);
        } else {
            var block = (CompoundStatement) statement("{" + source + "}");
            anchor.replaceBy(block);
            block.getStatements().add(after ? 0 : block.getStatements().size(), anchor);
        }
    }

    public void prepend(String source) { inject(source, ASTInjectionPoint.BEFORE_DECLARATIONS); }

    public void append(String source) { inject(source, ASTInjectionPoint.END); }

    private void inject(String source, ASTInjectionPoint point) {
        var added = parse(source);
        inject(added, point, _ -> true);
        comments = point == ASTInjectionPoint.END ? comments + added.comments : added.comments + comments;
    }

    public void inject(GlslAst source, ASTInjectionPoint point, Predicate<ExternalDeclaration> selected) {
        var children = source.tree.getChildren();
        var insertion = point.getInjectionIndex(tree);
        for (var index = 0; index < children.size();) {
            var node = children.get(index);
            if (!selected.test(node)) { index++; continue; }
            node.detach();
            tree.getChildren().add(insertion++, node);
        }
    }

    public void extension(String name) {
        tree.parseAndInjectNode(PARSER, ASTInjectionPoint.BEFORE_ALL, "#extension " + name + " : require\n");
    }

    public String source() { return print(tree) + '\n' + comments; }

    public String body() {
        return tree.getChildren().stream().filter(node -> !(node instanceof ExtensionDirective))
                .map(GlslAst::print).collect(Collectors.joining("\n")) + '\n' + comments;
    }

    public String extensions() {
        return tree.getChildren().stream().filter(ExtensionDirective.class::isInstance)
                .map(GlslAst::print).collect(Collectors.joining("\n")) + '\n';
    }

    public static String print(ASTNode node) { return ASTPrinter.printIndented(node); }

    public static boolean matches(Expression node, String pattern) {
        return new Matcher<>(PARSER.parseExpression(RootSupplier.DEFAULT, pattern)).matches(node);
    }

    public static boolean storage(TypeAndInitDeclaration declaration, StorageQualifier.StorageType type) {
        return storage(declaration.getType().getTypeQualifier(), type);
    }

    public static boolean storage(TypeQualifier qualifiers, StorageQualifier.StorageType type) {
        return qualifiers != null && qualifiers.getParts().stream()
                .anyMatch(part -> part instanceof StorageQualifier storage && storage.storageType == type);
    }

    public record Assignment(ASTNode node) {
        public boolean declared() { return node instanceof DeclarationMember; }
        public Expression value() {
            return node instanceof AssignmentExpression assignment ? assignment.getRight()
                    : ((ExpressionInitializer) ((DeclarationMember) node).getInitializer()).getExpression();
        }
        public void value(Expression value) {
            if (node instanceof AssignmentExpression assignment) assignment.setRight(value);
            else ((ExpressionInitializer) ((DeclarationMember) node).getInitializer()).setExpression(value);
        }
        public Statement statement() { return node.getAncestor(Statement.class); }
    }
}
