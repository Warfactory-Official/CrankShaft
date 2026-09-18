package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.MultiplicationExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;

public final class SundialTranslucent {
    public static final String MARKER = "_flw_sundialTranslucent";
    public static final String SHADOW_MARKER = "_flw_sundialShadowTranslucent";

    private SundialTranslucent() {
    }

    public static String shadowVertex(String vertex) {
        var parser = GuestShaders.versionedTransformer();
        int[] changed = {0};
        parser.setTransformation((tree, root) -> {
            for (var member : root.nodeIndex.getStream(DeclarationMember.class).toList()) {
                if (!member.getName().getName().equals("isTransparent")) continue;
                if (!(member.getInitializer() instanceof ExpressionInitializer initializer)
                        || !(initializer.getExpression() instanceof MultiplicationExpression expression)
                        || !ASTPrinter.printSimple(expression.getRight()).contains("isWater")) {
                    throw new IllegalStateException("Sundial translucent shadow classification changed");
                }
                // The native translucent pass classifies by draw category, not the centre texel's alpha.
                initializer.setExpression(parser.parseExpression(root, ASTPrinter.printSimple(expression.getRight())));
                changed[0]++;
            }
        });
        String adapted = parser.transform(vertex);
        if (changed[0] != 1)
            throw new IllegalStateException("Sundial shadow program has no unique transparency classification");
        return adapted;
    }

    public static String adapt(String vertex, String fragment) {
        var parser = GuestShaders.versionedTransformer();
        String[] remap = {null};
        parser.setTransformation((tree, root) -> {
            for (var identifier : root.identifierIndex.getStream("gl_MultiTexCoord1").toList()) {
                if (identifier.getParent() instanceof ReferenceExpression reference) {
                    // Colorwheel light coordinates are texel-centred; restore the pack's native input units.
                    reference.replaceByAndDelete(parser.parseExpression(root,
                            "vec4(_flw_contractLight * 256.0 - 8.0, 0.0, 1.0)"));
                }
            }
            FunctionDefinition main = root.nodeIndex.getStream(FunctionDefinition.class)
                                                    .filter(function -> function.getFunctionPrototype().getName()
                                                                                .getName().equals("main"))
                                                    .findFirst().orElseThrow();
            for (var statement : main.getBody().getStatements()) {
                if (statement instanceof ExpressionStatement expression
                        && expression.getExpression() instanceof AssignmentExpression assignment
                        && ASTPrinter.printSimple(assignment.getLeft()).replaceAll("\\s+", "")
                                     .equals("texlmcoord.pq")) {
                    remap[0] = ASTPrinter.printSimple(assignment.getRight());
                }
            }
        });
        parser.transform(vertex);
        if (remap[0] == null || !remap[0].contains("_flw_contractLight")) {
            throw new IllegalStateException("Sundial translucent vertex program has no recognized lightmap mapping");
        }
        int[] changed = {0};
        parser.setTransformation((tree, root) -> {
            for (var assignment : root.nodeIndex.getStream(AssignmentExpression.class).toList()) {
                if (!ASTPrinter.printSimple(assignment.getLeft()).replaceAll("\\s+", "").equals("rawData.lightmap")
                        || !ASTPrinter.printSimple(assignment.getRight()).replaceAll("\\s+", "")
                                      .equals("texlmcoord.pq")) continue;
                if (!(assignment.getParent() instanceof ExpressionStatement statement)) {
                    throw new IllegalStateException("Sundial translucent lightmap assignment changed shape");
                }
                statement.replaceByAndDelete(parser.parseStatement(root, """
                        {
                            float _flw_contractAo;
                            vec4 _flw_contractOverlay;
                            vec2 _flw_contractLight;
                            clrwl_computeFragment(albedoData, rawData.albedo, _flw_contractLight,
                                    _flw_contractAo, _flw_contractOverlay);
                            rawData.albedo.rgb = mix(rawData.albedo.rgb, _flw_contractOverlay.rgb, _flw_contractOverlay.a);
                            rawData.lightmap = %s;
                        }
                        """.formatted(remap[0])));
                changed[0]++;
            }
        });
        String adapted = parser.transform(fragment);
        if (changed[0] != 1)
            throw new IllegalStateException("Sundial translucent fragment program has no unique material setup");
        return adapted;
    }
}
