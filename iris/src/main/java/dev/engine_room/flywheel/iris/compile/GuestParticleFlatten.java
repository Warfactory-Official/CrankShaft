package dev.engine_room.flywheel.iris.compile;

import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeFunction;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A single-record particle program ({@code GuestProgramResolver.singleRecordParticles}) gets a cloud composited: its
 * layers, submitted back to front, blend over each other in primitive order into the flatten target
 * ({@link PackRole#PARTICLES_FLATTEN}), then every cloudlet fragment the material keeps hands the pack's own program
 * that composite as its albedo ({@link PackRole#PARTICLES_HANDOFF}); the nearest one's record survives with its own
 * depth. The flatten pass also records the nearest kept layer's instance ({@link #TARGET_IDS}, tested against its own
 * copy of the main depth); the hand-off demotes every other instance's fragments before the pack's program runs, so
 * only that layer runs it to effect (iterationRP's depth image, SEUS's capture) and writes depth itself where the pack
 * keeps it; another instance sharing the id is ordered by that depth test. Both passes rasterize the pack's vertex
 * stage, its colour input white ({@link #VERTEX_OUTPUTS} carry the cloudlet's), so jitter and scale agree and the
 * hand-off covers no pixel the composite missed.
 */
final class GuestParticleFlatten {
    static final String HANDOFF_COLOR = "_flw_handoffColor";
    static final String UV = "_flw_flattenUv";
    static final String ID = "_flw_flattenId";
    static final String VERTEX_OUTPUTS = "out vec4 " + HANDOFF_COLOR + ";\nout vec2 " + UV + ";\nflat out float " + ID
            + ";\n";
    static final String TARGET = "_flw_flatten";
    static final String TARGET_IDS = "_flw_flattenIds";
    private static final Set<String> SAMPLE = Set.of("texture", "texture2D", "textureLod", "textureGrad",
            "textureOffset", "textureLodOffset", "textureGradOffset");
    // Its inputs are declared after Iris's patch (flatten), which would otherwise give the pack's vertex stage zeroed
    // outputs for them.
    private static final String FRAGMENT = """
            #version 330 compatibility
            uniform sampler2D gtexture;
            void main() {
                /* DRAWBUFFERS:01 */
                gl_FragData[0] = texture(gtexture, %s) * %s;
                gl_FragData[1] = vec4(%s, 0.0, 0.0, 1.0);
            }
            """.formatted(UV, HANDOFF_COLOR, ID);
    // The id overwrites.
    static final List<BufferBlendInformation> BUFFER_BLENDS = List.of(new BufferBlendInformation(1, null));
    // Straight colour over the zero-cleared target accumulates it premultiplied.
    private static final BlendModeOverride OVER = new BlendModeOverride(new BlendMode(
            BlendModeFunction.SRC_ALPHA.getGlId(), BlendModeFunction.ONE_MINUS_SRC_ALPHA.getGlId(),
            BlendModeFunction.ONE.getGlId(), BlendModeFunction.ONE_MINUS_SRC_ALPHA.getGlId()));

    private GuestParticleFlatten() {
    }

    /**
     * The pack's particle vertex stage and the cloudlet's colour, the material's cutout appended where a pack's would
     * be.
     */
    static ProgramSource source(ProgramSource particles) {
        return new ProgramSource("crankshaft_particle_flatten", particles.getVertexSource().orElseThrow(), null, null,
                null, FRAGMENT, particles.getParent(), null, OVER);
    }

    static String flatten(String fragment) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, _) -> tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                Stream.of("in vec4 " + HANDOFF_COLOR + ";\n", "in vec2 " + UV + ";\n", "flat in float " + ID + ";\n")));
        return parser.transform(fragment);
    }

    /**
     * The pack's particle program, its albedo samples replaced by the composite; a fragment the material's cutout drops
     * ({@code own}, {@code predicate}) leaves no record. {@code demote}: GL_EXT_demote_to_helper_invocation, which keeps the
     * pack's derivatives defined where the nearest layer meets another; else discard.
     */
    static String handoff(String fragment, AlphaTest own, @Nullable String predicate, boolean demote) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            boolean replaced = false;
            for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (call.getFunctionName() == null || !SAMPLE.contains(call.getFunctionName().getName())
                        || call.getParameters().isEmpty()
                        || !(call.getParameters().getFirst() instanceof ReferenceExpression sampler)
                        || !GuestShaders.ALBEDO.contains(sampler.getIdentifier().getName())) continue;
                call.replaceByAndDelete(
                        parser.parseExpression(root, "_flw_handoff(" + ASTPrinter.printSimple(call) + ")"));
                replaced = true;
            }
            if (!replaced) throw new IllegalStateException("Particle program samples no albedo");
            boolean quadRejection = GuestFlattenTarget.QUAD_REJECTION;
            if (demote || quadRejection) GuestShaders.preamble(parser, tree, compilation -> {
                if (demote) compilation.requireExtension("GL_EXT_demote_to_helper_invocation");
                if (quadRejection) compilation.requireExtension("GL_ARB_derivative_control");
            });
            // Fine differences propagate a nonnegative winner mask across its row, then the derivative quad.
            tree.getOneMainDefinitionBody().getStatements().addFirst(parser.parseStatement(root,
                    "{ uint flw_winner = " + ID + " == texelFetch(" + TARGET_IDS
                            + ", ivec2(gl_FragCoord.xy), 0).r ? 1u : 0u;"
                            + (quadRejection ? " float flw_selected = float(flw_winner);"
                            + " float flw_row = flw_selected + abs(dFdxFine(flw_selected));"
                            + " if (flw_row + abs(dFdyFine(flw_row)) == 0.0) discard;" : "")
                            + " if (flw_winner == 0u) " + (demote ? "demote;" : "discard;") + " }"));
            boolean declared = root.identifierIndex.has("flw_discardPredicate");
            tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, Stream.of(
                    "uniform sampler2D " + TARGET + ";\n",
                    "uniform sampler2D " + TARGET_IDS + ";\n",
                    "in vec4 " + HANDOFF_COLOR + ";\n",
                    "flat in float " + ID + ";\n",
                    "vec4 _flw_handoff(vec4 flw_sampled);\n"));
            if (predicate != null && !declared) tree.parseAndInjectNode(parser, ASTInjectionPoint.END, predicate);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END, "vec4 _flw_handoff(vec4 flw_sampled) {"
                    + " vec4 flw_own = flw_sampled * " + HANDOFF_COLOR + ";"
                    + own.toExpression("flw_own.a", Float.toString(own.reference()), "")
                    + (predicate == null ? "" : "if (flw_discardPredicate(flw_own)) discard;")
                    + " vec4 flw_composite = texelFetch(" + TARGET + ", ivec2(gl_FragCoord.xy), 0);"
                    + " if (flw_composite.a <= 0.0) discard;"
                    + " return vec4(flw_composite.rgb / flw_composite.a, flw_composite.a); }");
        });
        return parser.transform(fragment);
    }
}
