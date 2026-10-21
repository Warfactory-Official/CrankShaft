package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import io.github.douira.glsl_transformer.ast.node.expression.binary.MultiplicationExpression;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DiscardStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;

import java.util.Map;
import java.util.Comparator;

final class SundialTranslucent {
    static final String MARKER = "_flw_sundialTranslucent";
    static final String SHADOW_MARKER = "_flw_sundialShadowTranslucent";

    private SundialTranslucent() { }

    static String shadowVertex(String vertex, String fragment) {
        var fragmentAst = GlslAst.parse(fragment);
        var statements = fragmentAst.function("main").getBody().getStatements();
        if (statements.size() == 1 && statements.getFirst() instanceof DiscardStatement) return vertex;
        var ast = GlslAst.parse(vertex);
        var found = ast.assignments("isTransparent");
        if (found.size() != 1) throw new UnsupportedOperationException("Sundial shadow transparency classification changed");
        if (!(found.getFirst().value() instanceof MultiplicationExpression product) || !ast.within(product.getRight(), "isWater"))
            throw new UnsupportedOperationException("Sundial shadow transparency factor changed");
        // 1.12.2: the translucent draw category owns classification, independently of a centre texel's alpha.
        found.getFirst().value(product.getRight().cloneInto(ast.tree.getRoot()));
        return ast.source();
    }

    static String adapt(String vertex, String fragment) {
        var vertexAst = GlslAst.parse(vertex);
        // 1.12.2: light values enter the native vertex program in uncentred 0..240 units.
        vertexAst.references(Map.of("gl_MultiTexCoord1", "vec4(_flw_contractLight*256.0,0.0,1.0)"));
        var main = vertexAst.function("main").getBody();
        var mappings = vertexAst.assignments("texlmcoord.pq").stream()
                .filter(value -> value.statement().getParent() == main)
                .sorted(Comparator.comparingInt(value -> main.getStatements().indexOf(value.statement()))).toList();
        if (mappings.isEmpty() || !vertexAst.within(mappings.getLast().value(), "_flw_contractLight"))
            throw new UnsupportedOperationException("Sundial translucent lightmap mapping changed");
        // Iris's active branch can override the initial mapping; the final assignment owns the native light input.
        var mapping = GlslAst.print(mappings.getLast().value());
        var ast = GlslAst.parse(fragment);
        var changed = 0;
        for (var assignment : ast.assignments("rawData.lightmap")) {
            if (!GlslAst.matches(assignment.value(), "texlmcoord.pq")) continue;
            assignment.statement().replaceByAndDelete(ast.statement("""
                    {
                        float _flw_contractAo;
                        vec4 _flw_contractOverlay;
                        vec2 _flw_contractLight;
                        clrwl_computeFragment(albedoData,rawData.albedo,_flw_contractLight,_flw_contractAo,_flw_contractOverlay);
                        if(rawData.albedo.a<=0.0)discard;
                        rawData.albedo.rgb=mix(rawData.albedo.rgb,_flw_contractOverlay.rgb,_flw_contractOverlay.a);
                        rawData.lightmap=%s;
                    }
                    """.formatted(mapping)));
            changed++;
        }
        if (changed != 1) throw new UnsupportedOperationException("Sundial translucent material setup changed");
        var capture = ast.has("flw_captureUnlit");
        var calls = ast.calls("packUpGbufferDataSolid").toList();
        if (calls.size() != 1) throw new UnsupportedOperationException("Sundial translucent material packing changed");
        if (capture) {
            var statement = calls.getFirst().getAncestor(ExpressionStatement.class);
            ast.insertBefore(statement, "flw_setCaptureUnlit(_flw_isUnlit());");
        }
        return DeferredReplayShaders.compatibilityHeader(fragment)
                + (capture ? "void flw_setCaptureUnlit(bool unlit);\n" : "") + ast.body();
    }
}
