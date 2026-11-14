package dev.engine_room.flywheel.iris.compile.patches;

import com.google.common.collect.Iterables;
import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Borrows the active pack's transparent RGB filter while assembling forward materials on the render thread.
 */
public final class SeusGlassShaders {
    private static final String FUNCTION = "_flw_forwardSeusGlassTint";
    private static final Pattern ALBEDO = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\.\\s*albedo\\b");
    private static final Pattern NORMALIZE = Pattern.compile("\\bnormalize\\s*\\(");
    private static final Pattern POW = Pattern.compile("\\bpow\\s*\\(");
    private static final Pattern COLOR = Pattern.compile("\\s*([A-Za-z_]\\w*)(?:\\s*\\.\\s*(?:rgb|xyz))?\\s*");

    private SeusGlassShaders() {
    }

    /**
     * Classic SEUS supplies composite; GFME supplies composite1, both after native preprocessing.
     */
    public static void inject(TranslationUnit target, Root targetRoot, ASTParser targetParser, String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((_, root) -> {
            var declaration = Iterables.getOnlyElement(root.nodeIndex.getStream(TypeAndInitDeclaration.class)
                                                                     .filter(value -> tintDeclaration(value, root))
                                                                     .toList());
            var albedo = ALBEDO.matcher(ASTPrinter.printSimple(declaration));
            albedo.find();
            String material = albedo.group(1);
            var block = declaration.getAncestor(CompoundStatement.class);
            List<Statement> statements = block.getStatements().stream()
                                              .filter(statement -> statement instanceof DeclarationStatement || statement instanceof ExpressionStatement)
                                              .toList();
            var write = (ExpressionStatement) statements.stream()
                                                        .filter(ExpressionStatement.class::isInstance).findFirst()
                                                        .orElseThrow();
            var color = COLOR.matcher(ASTPrinter.printSimple(((BinaryExpression) write.getExpression()).getLeft()));
            color.matches();
            String background = color.group(1);
            target.parseAndInjectNode(targetParser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "vec3 " + FUNCTION + "(vec4 albedo);");
            target.parseAndInjectNode(targetParser, ASTInjectionPoint.END,
                    "vec3 " + FUNCTION + "(vec4 _flw_albedo) { GBufferDataTransparent " + material + "; "
                            + material + ".albedo = _flw_albedo; vec3 " + background + " = vec3(1.0); return " + background + "; }");
            var body = targetRoot.nodeIndex.getStream(FunctionDefinition.class)
                                           .filter(function -> function.getFunctionPrototype().getName().getName()
                                                                       .equals(FUNCTION))
                                           .findFirst().orElseThrow().getBody();
            for (var statement : statements)
                body.getStatements().add(body.getStatements().size() - 1, statement.cloneInto(targetRoot));
        });
        parser.transform(source);
    }

    private static boolean tintDeclaration(TypeAndInitDeclaration declaration, Root root) {
        String text = ASTPrinter.printSimple(declaration);
        var material = ALBEDO.matcher(text);
        if (!NORMALIZE.matcher(text).find() || !POW.matcher(text).find() || !material.find()
                || !declaration.hasAncestor(FunctionDefinition.class)) return false;
        String name = material.group(1);
        var function = declaration.getAncestor(FunctionDefinition.class);
        var type = Pattern.compile("\\bGBufferDataTransparent\\s+" + Pattern.quote(name) + "\\b");
        return root.nodeIndex.getStream(TypeAndInitDeclaration.class)
                             .anyMatch(
                                     value -> value.hasAncestor(function) && type.matcher(ASTPrinter.printSimple(value))
                                                                                 .find());
    }
}
