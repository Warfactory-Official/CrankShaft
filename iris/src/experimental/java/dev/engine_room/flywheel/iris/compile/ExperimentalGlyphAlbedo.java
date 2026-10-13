package dev.engine_room.flywheel.iris.compile;

import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;

import java.util.Set;

/**
 * Maps R8 glyph coverage to white RGB/red alpha without changing the texture.
 */
final class ExperimentalGlyphAlbedo {
    private static final Set<String> ALBEDO = Set.of("tex", "texture", "gtexture", "u_MainSampler", "Sampler0");
    private static final Set<String> SAMPLE = Set.of("texture", "textureLod", "textureGrad", "textureProj",
            "textureProjLod", "textureProjGrad", "texelFetch", "texelFetchOffset", "textureOffset", "textureLodOffset",
            "textureGradOffset", "textureProjOffset", "textureProjLodOffset", "textureProjGradOffset");

    private ExperimentalGlyphAlbedo() {
    }

    static String patch(String source) {
        var parser = new SingleASTTransformer<JobParameters>();
        var tree = parser.parseSeparateTranslationUnit(source);
        var root = tree.getRoot();
        if (root.identifierIndex.has("_flw_glyphAlbedo"))
            throw new IllegalStateException("Glyph albedo helper name collision");
        boolean changed = false;
        for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
            if (call.getFunctionName() == null || !SAMPLE.contains(
                    call.getFunctionName().getName()) || call.getParameters().isEmpty()
                    || !(call.getParameters().getFirst() instanceof ReferenceExpression sampler)
                    || !ALBEDO.contains(sampler.getIdentifier().getName())) continue;
            call.replaceByAndDelete(
                    parser.parseExpression(root, "_flw_glyphAlbedo(" + ASTPrinter.printSimple(call) + ")"));
            changed = true;
        }
        if (!changed) return source;
        tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "vec4 _flw_glyphAlbedo(vec4 sampled) { return vec4(1.0, 1.0, 1.0, sampled.r); }");
        return ASTPrinter.print(PrintType.INDENTED, tree);
    }
}
