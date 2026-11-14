package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanEqualExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanEqualExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.MultiplicationAssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.statement.selection.SelectionStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DiscardStatement;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;

import java.util.List;
import java.util.Set;

/**
 * Supplies DH's decoded vertex inputs from one retained surface pixel; the pack keeps both shader stages.
 */
public final class FarDhShaders {
    private static final SingleASTTransformer<JobParameters> TRANSFORMER = GuestShaders.versionedTransformer();
    private static final SingleASTTransformer<JobParameters> FRAGMENT = GuestShaders.versionedTransformer();
    private static final Set<String> ATTRIBUTES = Set.of("vPosition", "iris_color", "irisExtra");
    private static final String INPUTS = """
            uniform sampler2D _flw_farColor;
            uniform sampler2D _flw_farNormal;
            uniform sampler2D _flw_farLight;
            uniform sampler2D _flw_farDepth;
            uniform usamplerBuffer _flw_farPixels;
            uniform usampler2D _flw_farMaterial;
            uniform mat4 _flw_farInverseProjection;
            uniform mat4 _flw_farViewToWorld;
            uniform float _flw_farMainRadius;
            vec3 _flw_farCenter;
            vec3 _flw_farPosition(vec2 uv, float depth, vec3 center, vec3 viewNormal, ivec2 size,
                    mat4 inverseProjection, mat4 viewToWorld);
            void _flw_farInit() {
                ivec2 size = textureSize(_flw_farDepth, 0);
                uint pixel = texelFetch(_flw_farPixels, gl_InstanceID).r;
                ivec2 at = ivec2(int(pixel) % size.x, int(pixel) / size.x);
                vec2 uv = (vec2(at) + 0.5) / vec2(size);
                float depth = texelFetch(_flw_farDepth, at, 0).r;
                vec4 view = _flw_farInverseProjection * vec4(uv * 2.0 - 1.0,
                        depth, 1.0);
                vec3 center = view.xyz / view.w;
                _flw_farCenter = (_flw_farViewToWorld * vec4(center, 1.0)).xyz;
                vec3 normal = normalize(texelFetch(_flw_farNormal, at, 0).xyz * 2.0 - 1.0);
                vec3 viewNormal = inverse(mat3(_flw_farViewToWorld)) * normal;
                vec3 corner = _flw_farPosition(uv, depth, center, viewNormal, size,
                        _flw_farInverseProjection, _flw_farViewToWorld);
                vec3 world = (_flw_farViewToWorld * vec4(corner, 1.0)).xyz;
                vec4 light = texelFetch(_flw_farLight, at, 0);
                uvec4 material = texelFetch(_flw_farMaterial, at, 0);
                _vert_position = world;
                _vert_normal = normal;
                _vert_color = vec4(texelFetch(_flw_farColor, at, 0).rgb, 1.0);
                _vert_tex_light_coord = mix(light.xy + 1.0 / 32.0, vec2(15.5 / 16.0), light.z);
                dhMaterialId = int((material.w >> 25u) & 15u);
                iris_vBlockPos = world;
                iris_TexId = uvec2(0u);
            }
            """;
    private static final String QUAD_POSITION = """
            const vec2 _flw_farCorners[4] = vec2[4](
                    vec2(-0.5, -0.5), vec2(0.5, -0.5), vec2(0.5, 0.5),
                    vec2(-0.5, 0.5));
            vec3 _flw_farPosition(vec2 uv, float depth, vec3 center, vec3 viewNormal, ivec2 size,
                    mat4 inverseProjection, mat4 viewToWorld) {
                vec2 cornerUv = uv + _flw_farCorners[gl_VertexID] / vec2(size);
                vec4 projected = inverseProjection * vec4(cornerUv * 2.0 - 1.0, depth, 1.0);
                vec3 ray = projected.xyz / projected.w;
                return ray * (dot(viewNormal, center) / dot(viewNormal, ray));
            }
            """;

    static {
        TRANSFORMER.setTransformation((tree, root) -> {
            for (var external : tree.getChildren()) {
                if (external instanceof DeclarationExternalDeclaration declaration
                        && declaration.getDeclaration() instanceof TypeAndInitDeclaration typed
                        && typed.getMembers().size() == 1
                        && ATTRIBUTES.contains(typed.getMembers().getFirst().getName().getName())) {
                    typed.getType().setTypeQualifier(null);
                }
            }
            for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (call.getFunctionName() != null && call.getFunctionName().getName().equals("_vert_init")) {
                    call.replaceByAndDelete(TRANSFORMER.parseExpression(root, "_flw_farInit()"));
                }
            }
            Compilation library = new Compilation();
            library.appendComponent(new RawSource("far_dh_inputs", INPUTS));
            library.appendComponent(new RawSource("far_dh_position", QUAD_POSITION));
            injectLibrary(TRANSFORMER, tree, ASTInjectionPoint.BEFORE_FUNCTIONS, library);
            tree.appendMainFunctionBody(TRANSFORMER,
                    "if (_flw_farMainRadius > 0.0 && length(_flw_farCenter.xz) > _flw_farMainRadius) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);");
        });
        FRAGMENT.setTransformation((tree, root) -> {
            var farReferences = root.nodeIndex.getStream(ReferenceExpression.class)
                                              .filter(reference -> reference.getIdentifier().getName().equals("far"))
                                              .toList();
            var comparisons = List.<Class<? extends BinaryExpression>>of(LessThanExpression.class,
                                          LessThanEqualExpression.class, GreaterThanExpression.class, GreaterThanEqualExpression.class)
                                  .stream().flatMap(type -> root.nodeIndex.getStream(type)).toList();
            for (var discard : root.nodeIndex.getStream(DiscardStatement.class).toList()) {
                var selection = discard.getAncestor(SelectionStatement.class);
                if (selection == null || !(discard == selection.getIfTrue()
                        || discard.hasAncestor(selection.getIfTrue()))) continue;
                var condition = selection.getCondition();
                boolean handoff = comparisons.stream().anyMatch(comparison -> {
                    if (!(comparison == condition || comparison.hasAncestor(condition))) return false;
                    var threshold = comparison instanceof LessThanExpression || comparison instanceof LessThanEqualExpression
                            ? comparison.getRight() : comparison.getLeft();
                    return farReferences.stream().anyMatch(
                            reference -> reference == threshold || reference.hasAncestor(threshold));
                });
                if (handoff) discard.replaceByAndDelete(FRAGMENT.parseStatement(root,
                        "{ if (_flw_farMainRadius <= 0.0) discard; }"));
            }
            for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (call.getFunctionName() == null || !call.getFunctionName().getName().equals("smoothstep")
                        || call.getParameters().size() != 3) continue;
                boolean nearHandoff = call.getParameters().subList(0, 2).stream().allMatch(parameter ->
                        farReferences.stream().anyMatch(
                                reference -> reference == parameter || reference.hasAncestor(parameter)));
                var multiply = call.getAncestor(MultiplicationAssignmentExpression.class);
                if (!nearHandoff || multiply == null
                        || !(multiply.getLeft() instanceof MemberAccessExpression alpha)
                        || !Set.of("a", "w").contains(alpha.getMember().getName())) continue;
                var replacement = (FunctionCallExpression) FRAGMENT.parseExpression(root,
                        "_flw_farHandoff(_flw_farAlpha)");
                call.replaceBy(replacement);
                replacement.getParameters().getFirst().replaceByAndDelete(call);
            }
            Compilation library = new Compilation();
            library.appendComponent(new RawSource("far_dh_handoff", """
                    uniform float _flw_farMainRadius;
                    float _flw_farHandoff(float alpha) {
                        return _flw_farMainRadius > 0.0 ? 1.0 : alpha;
                    }
                    """));
            injectLibrary(FRAGMENT, tree, ASTInjectionPoint.BEFORE_DECLARATIONS, library);
        });
    }

    private FarDhShaders() {
    }

    private static void injectLibrary(SingleASTTransformer<JobParameters> parser, TranslationUnit target,
                                      ASTInjectionPoint point, Compilation source) {
        source.version(GuestShaders.glslVersion());
        var library = parser.parseTranslationUnit(target.getRoot(), source.assembledSource());
        target.injectNodes(point,
                library.getChildren().stream().map(node -> node.cloneInto(target.getRoot())).toList());
        library.detachAndDelete();
    }

    public static String vertex(String patchedVertex) {
        return TRANSFORMER.transform(patchedVertex);
    }

    public static String fragment(String patchedFragment) {
        return FRAGMENT.transform(patchedFragment);
    }
}
