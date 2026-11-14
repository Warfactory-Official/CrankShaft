package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import dev.engine_room.flywheel.api.material.Transparency;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;

import java.util.Set;

/**
 * Feeds one reconstructed surface through both native DH translucent stages.
 */
public final class FarLayerDhShaders {
    private FarLayerDhShaders() {
    }

    public static String vertex(String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            if (tree.getVersionStatement().version.compareTo(Version.fromNumber(430)) < 0)
                tree.getVersionStatement().version = Version.fromNumber(430);
            GuestShaders.preamble(parser, tree,
                    compilation -> compilation.requireExtension("GL_ARB_shader_draw_parameters"));
            for (var external : tree.getChildren()) {
                if (external instanceof DeclarationExternalDeclaration declaration
                        && declaration.getDeclaration() instanceof TypeAndInitDeclaration typed
                        && typed.getMembers().size() == 1
                        && Set.of("vPosition", "iris_color", "irisExtra")
                              .contains(typed.getMembers().getFirst().getName().getName()))
                    typed.getType().setTypeQualifier(null);
            }
            for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (call.getFunctionName() != null && call.getFunctionName().getName().equals("_vert_init"))
                    call.replaceByAndDelete(parser.parseExpression(root, "_flw_farLayerInit()"));
            }
            var library = new Compilation();
            library.version(GuestShaders.glslVersion());
            library.appendComponent(new RawSource("far layer records", FarLayerPixels.HITS));
            library.appendComponent(new RawSource("far layer DH inputs", INPUTS));
            var declarations = parser.parseTranslationUnit(root, library.assembledSource());
            tree.injectNodes(ASTInjectionPoint.BEFORE_FUNCTIONS,
                    declarations.getChildren().stream().map(node -> node.cloneInto(root)).toList());
            declarations.detachAndDelete();
        });
        return parser.transform(GuestShaders.shiftBufferBindings(source));
    }

    public static Stages patch(String vertex, String fragment) {
        boolean[] materialMaps = {false};
        return new Stages(vertex(vertex), fragment(fragment, materialMaps), materialMaps[0]);
    }

    private static String fragment(String source, boolean[] materialMaps) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            if (tree.getVersionStatement().version.compareTo(Version.fromNumber(430)) < 0)
                tree.getVersionStatement().version = Version.fromNumber(430);
            var library = new Compilation();
            library.version(GuestShaders.glslVersion());
            FarVoxyNormals.patchDhGeometry(parser, tree);
            materialMaps[0] = FarMaterialTextures.redirect(parser, tree, root, "_flw_farLayerUv");
            library.appendComponent(new RawSource("far layer coverage", """
                    flat in vec3 _flw_staticNormal;
                    flat in vec4 _flw_farLayerTangent;
                    flat in vec2 _flw_farLayerUv;
                    vec4 _flw_staticTangent() { return _flw_farLayerTangent; }
                    flat in vec3 _flw_farLayerCenter;
                    flat in uint _flw_farLayerFlags;
                    uniform bool _flw_farLayerShadow;
                    uniform bool _flw_farLayerOpaqueTest;
                    uniform sampler2D _flw_farLayerHostOpaque;
                    uniform sampler2D _flw_farLayerMainOpaque;
                    uniform mat4 _flw_farLayerHostClip;
                    uniform mat4 _flw_farLayerMainClip;
                    uniform bool _flw_farLayerHostZeroToOne;
                    uniform float _flw_farLayerRasterScale;
                    bool _flw_farLayerHidden(sampler2D depth,mat4 projection,bool zeroToOne) {
                        vec4 clip=projection*vec4(_flw_farLayerCenter,1.0);
                        vec2 uv=clip.xy/clip.w*0.5+0.5;
                        if(any(lessThan(uv,vec2(0.0)))||any(greaterThanEqual(uv,vec2(1.0)))) return false;
                        float z=clip.z/clip.w;
                        if(!zeroToOne) z=z*0.5+0.5;
                        float opaque=texture(depth,uv*_flw_farLayerRasterScale).r;
                        return opaque!=0.0 && opaque>z;
                    }
                    uniform float _flw_farLayerRadius;
                    uniform bool _flw_farLayerMain;
                    """));
            var declarations = parser.parseTranslationUnit(root, library.assembledSource());
            tree.injectNodes(ASTInjectionPoint.BEFORE_DECLARATIONS,
                    declarations.getChildren().stream().map(node -> node.cloneInto(root)).toList());
            declarations.detachAndDelete();
            tree.prependMainFunctionBody(parser,
                    "if (_flw_farLayerOpaqueTest && (_flw_farLayerHidden(_flw_farLayerHostOpaque,_flw_farLayerHostClip,_flw_farLayerHostZeroToOne)"
                            + " || _flw_farLayerHidden(_flw_farLayerMainOpaque,_flw_farLayerMainClip,true))) discard;",
                    "if (!_flw_farLayerShadow && (length(_flw_farLayerCenter.xz) <= _flw_farLayerRadius) != _flw_farLayerMain) discard;",
                    "if (_flw_farLayerShadow) { uint transparency = (_flw_farLayerFlags >> 8u) & 255u;\n"
                            + "if ((_flw_farLayerFlags & 65536u) == 0u || transparency == " + Transparency.ADDITIVE.ordinal()
                            + "u || transparency == " + Transparency.LIGHTNING.ordinal() + "u || transparency == "
                            + Transparency.ORDER_INDEPENDENT_ADDITIVE.ordinal() + "u) discard; }");
        });
        return parser.transform(GuestShaders.shiftBufferBindings(FarDhShaders.fragment(source)));
    }

    private static final String INPUTS = """
            layout(std430, binding = 25) readonly buffer _FlwFarHitMapping { uint _flw_farHitMapping[]; };
            uniform mat4 _flw_farLayerInverseProjection;
            uniform mat4 _flw_farLayerViewToWorld;
            uniform mat4 _flw_farLayerWorldToView;
            uniform vec2 _flw_farHitSize;
            uniform float _flw_farMainRadius;
            flat out vec3 _flw_farLayerCenter;
            flat out uint _flw_farLayerFlags;
            flat out vec3 _flw_staticNormal;
            flat out vec4 _flw_farLayerTangent;
            flat out vec2 _flw_farLayerUv;
            flat out uint _flw_farTextureSlot;
            const vec2 _flw_farLayerCorners[6] = vec2[6](
                    vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
                    vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 1.0));
            void _flw_farLayerInit() {
                uint index = _flw_farHitMapping[uint(gl_BaseInstanceARB) + uint(gl_InstanceID)];
                _FlwFarHit hit = _flw_farHits[index];
                _flw_farLayerCenter = hit.worldDepth.xyz;
                _flw_farLayerFlags = hit.address.z;
                _flw_staticNormal = normalize(hit.normal.xyz);
                _flw_farLayerTangent = hit.tangent;
                _flw_farLayerUv = uintBitsToFloat(hit.material.xy);
                _flw_farTextureSlot = uint(gl_DrawIDARB) + 16u;
                uint pixel = hit.address.w;
                uvec2 at = uvec2(pixel % uint(_flw_farHitSize.x), pixel / uint(_flw_farHitSize.x));
                vec2 uv = (vec2(at) + _flw_farLayerCorners[gl_VertexID]) / _flw_farHitSize;
                vec3 center = (_flw_farLayerWorldToView * vec4(hit.worldDepth.xyz, 1.0)).xyz;
                vec3 normal = normalize(hit.normal.xyz);
                vec3 viewNormal = transpose(mat3(_flw_farLayerViewToWorld)) * normal;
                vec4 pointA = _flw_farLayerInverseProjection * vec4(uv * 2.0 - 1.0, 0.25, 1.0);
                vec4 pointB = _flw_farLayerInverseProjection * vec4(uv * 2.0 - 1.0, 0.5, 1.0);
                vec3 origin = pointA.xyz / pointA.w;
                vec3 ray = pointB.xyz / pointB.w - origin;
                vec3 corner = origin + ray * (dot(viewNormal, center - origin) / dot(viewNormal, ray));
                vec3 world = (_flw_farLayerViewToWorld * vec4(corner, 1.0)).xyz;
                _vert_position = world;
                _vert_normal = normalize(hit.normal.xyz);
                _vert_color = vec4(hit.color.rgb, hit.color.a * hit.light.w);
                _vert_tex_light_coord = mix(clamp(hit.light.xy + 1.0 / 32.0,
                        vec2(0.5 / 16.0), vec2(15.5 / 16.0)), vec2(15.5 / 16.0), hit.light.z);
                dhMaterialId = int((hit.material.w >> 25u) & 15u);
                iris_vBlockPos = world;
                iris_TexId = uvec2(0u);
            }
            """;

    public record Stages(String vertex, String fragment, boolean materialMaps) {
    }
}
