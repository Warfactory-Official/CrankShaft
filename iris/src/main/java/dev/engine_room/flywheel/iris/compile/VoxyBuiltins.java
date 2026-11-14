package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Supplies Iris's non-glint builtin contract to terrain callbacks compiled outside TransformPatcher.
 */
public final class VoxyBuiltins {
    private static final Map<String, String> GLINT_HELPERS = Map.of(
            "mc_hasGlint", "bool mc_hasGlint() { return false; }",
            "mc_sampleGlint", "vec3 mc_sampleGlint() { return vec3(0.0); }");

    private VoxyBuiltins() {
    }

    public static String patch(String source) {
        if (source == null) return null;
        var parser = new SingleASTTransformer<JobParameters>();
        var tree = parser.parseSeparateTranslationUnit("#version 460 core\n" + source);
        var root = tree.getRoot();
        var defined = root.nodeIndex.getStream(FunctionDefinition.class)
                                    .map(function -> function.getFunctionPrototype().getName().getName())
                                    .collect(Collectors.toSet());
        var missing = root.nodeIndex.getStream(FunctionCallExpression.class)
                                    .filter(call -> call.getFunctionName() != null && call.getParameters().isEmpty())
                                    .map(call -> call.getFunctionName().getName())
                                    .filter(GLINT_HELPERS::containsKey).filter(name -> !defined.contains(name))
                                    .distinct().toList();
        if (missing.isEmpty()) return source;

        var library = new Compilation();
        library.version(GlslVersion.V460);
        for (String name : missing)
            library.appendComponent(new RawSource("Voxy builtin " + name, GLINT_HELPERS.get(name)));
        var helpers = parser.parseTranslationUnit(root, library.assembledSource());
        tree.injectNodes(ASTInjectionPoint.BEFORE_FUNCTIONS,
                helpers.getChildren().stream().map(node -> node.cloneInto(root)).toList());
        helpers.detachAndDelete();
        return tree.getChildren().stream().map(ASTPrinter::printSimple).collect(Collectors.joining("\n"));
    }
}
