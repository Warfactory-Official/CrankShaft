package dev.engine_room.flywheel.iris.compile.patches;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.engine_room.flywheel.iris.compile.GuestShaders;
import dev.engine_room.flywheel.iris.compile.NativeDepth;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;

import java.util.Map;

/**
 * Native LoD water and retained surfaces write the existing profile pool before nearest-front publication.
 */
public final class DeferredHostShaders {
    private DeferredHostShaders() {
    }

    /**
     * Pass zero captures every post-pack material. Pass one publishes the nearest native G-buffer/depth surface.
     * Coordinates stored in the pool and published at {@code frontLocation} share a precise main projection in the
     * pack's depth convention, including surfaces past its far plane. The caller reserves the first output location
     * after the native targets, or passes {@code -1} when the profile does not consume a published front.
     */
    public static String fragment(String source, DeferredOitProfile profile, int pass, boolean retained,
                                  int storageOffset, int frontLocation) {
        var demoted = GuestShaders.demoteForOit(source);
        var outputs = demoted.outputs();
        if (outputs.keySet().stream().anyMatch(location -> location < 0 || location >= outputs.size())
                || frontLocation >= 0 && frontLocation != outputs.size())
            throw new UnsupportedOperationException(
                    "Deferred host requires contiguous native outputs before its front target");
        boolean reversed = NativeDepth.active();
        String canonicalDepth = canonicalDepth(reversed, "canonicalNdc");
        return DeferredSources.transform(pass == 0 ? demoted.source() : source, (tree, root, parser) -> {
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename(pass == 0 ? "_flw_irisMain" : "main", "_flw_farDeferredPackMain");
            DeferredSources.inject(tree, parser, VIEW.replace("_FLW_MAIN_OPAQUE_HIDES", reversed
                            ? "main>_flw_farDeferredCoord.z" : "main>1.0-_flw_farDeferredCoord.z"),
                    ASTInjectionPoint.BEFORE_DECLARATIONS);
            String nativeDepth = root.identifierIndex.has("gl_FragDepth") ? "gl_FragDepth" : "gl_FragCoord.z";
            String position = retained
                    ? "precise vec4 viewPosition=_flw_farDeferredModelView*vec4(_flw_farLayerCenter,1.0);"
                    : """
                    precise vec2 uv=gl_FragCoord.xy/_flw_farDeferredSize;
                    precise float z=_FLW_NATIVE_DEPTH;
                    if(!_flw_farDeferredHostZeroToOne) z=z*2.0-1.0;
                    precise vec2 ndc=uv*(2.0/_flw_farDeferredRasterScale)-1.0;
                    precise vec4 viewPosition=_flw_farDeferredInverseHost*vec4(ndc,z,1.0);
                    viewPosition=viewPosition/viewPosition.w;
                    """.replace("_FLW_NATIVE_DEPTH", nativeDepth);
            DeferredSources.inject(tree, parser, """
                    void _flw_farDeferredCoordinates(out vec4 view,out float hostDepth) {
                    """ + position + """
                        precise vec4 clip=_flw_farDeferredMainFromHost*viewPosition;
                        precise float canonicalNdc=clip.z/clip.w;
                        precise float canonicalDepth=_FLW_CANONICAL_DEPTH;
                        _flw_farDeferredCoord=vec4(gl_FragCoord.xy,canonicalDepth,1.0/clip.w);
                        precise vec4 host=_flw_farDeferredHostProjection*viewPosition;
                        precise float nativeDepth=host.z/host.w;
                        if(!_flw_farDeferredHostZeroToOne) nativeDepth=nativeDepth*0.5+0.5;
                        view=viewPosition;
                        hostDepth=nativeDepth;
                    }
                    """.replace("_FLW_CANONICAL_DEPTH", canonicalDepth), ASTInjectionPoint.END);
            String coordinates = "vec4 view;float hostDepth;_flw_farDeferredCoordinates(view,hostDepth);";
            if (pass == 0) {
                if (!root.identifierIndex.has("screenSize"))
                    tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform vec2 screenSize;");
                if (!root.identifierIndex.has("depthtex1"))
                    tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                            "uniform sampler2D depthtex1;");
                String library;
                String capture;
                switch (profile) {
                    case SUNDIAL -> {
                        library = DeferredOitProfile.resource("layer_capture.glsl")
                                                    .replace("_FLW_CAPTURE_SNAPSHOT", "")
                                                    .replace("gl_FragCoord", "_flw_farDeferredCoord");
                        capture = "flw_captureLayer(" + output(outputs, 0) + "," + output(outputs, 1) + ","
                                + output(outputs, 2) + ");";
                    }
                    case ITERATION -> {
                        library = DeferredOitProfile.resource("iteration_layers.glsl")
                                + DeferredOitProfile.resource("iteration_materials.glsl");
                        String data = semantic(outputs, "gtransData", 0);
                        String albedo = outputs.values().stream().filter(value -> value.contains("albedo"))
                                               .findFirst().orElse("vec4(Unpack2xU8_from_U16(" + data
                                        + ".x),Unpack2xU8_from_U16(" + data + ".y))");
                        String water = outputs.values().stream().filter(value -> value.contains("gwater"))
                                              .findFirst().orElse("vec4(0.0)");
                        capture = "flw_captureMaterial(ivec2(_flw_farDeferredCoord.xy),_flw_farDeferredCoord.z,"
                                + albedo + "," + data + ","
                                + semantic(outputs, "gtransNormal", 1) + "," + water
                                + ",_flw_farDeferredWaterDepth,uint(screenSize.x));";
                        tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                                "vec2 _flw_farDeferredWaterDepth=vec2(0.0);");
                        root.nodeIndex.getStream(DeclarationMember.class)
                                      .filter(member -> member.getName().getName().equals("parallaxWaterDist"))
                                      .filter(member -> member.hasAncestor(FunctionDefinition.class)).toList()
                                      .forEach(member -> member.getAncestor(CompoundStatement.class)
                                                               .getStatements().add(parser.parseStatement(root,
                                                      "_flw_farDeferredWaterDepth=vec2(parallaxWaterDist,waterDist);")));
                    }
                    case SEUS, SEUS_HRR3, SEUS_GFME -> {
                        library = DeferredOitProfile.resource("layer_storage.glsl")
                                + DeferredOitProfile.resource("seus_capture.glsl")
                                                    .replace("_FLW_SEUS_OPAQUE_READY", "false")
                                                    .replace("gl_FragCoord", "_flw_farDeferredCoord");
                        capture = "flw_captureSeus(" + output(outputs, 0) + "," + output(outputs, 1) + ","
                                + (profile == DeferredOitProfile.SEUS_HRR3 && outputs.containsKey(2)
                                ? output(outputs, 2) + ".xyz" : "vec3(0.0)") + ");";
                    }
                    default -> throw new IllegalStateException("Unknown deferred host profile " + profile);
                }
                tree.parseAndInjectNode(parser, ASTInjectionPoint.END, "float flw_captureLimit(ivec2 pixel,float depth)"
                        + (reversed ? "{return -3.402823466e38;}" : "{return 3.402823466e38;}"));
                var storage = parser.parseTranslationUnit(root, GuestShaders.shiftBufferBindings(
                        "#version 450 core\n" + DeferredOitProfile.depthConvention(library, reversed), storageOffset));
                tree.injectNodes(ASTInjectionPoint.END,
                        storage.getChildren().stream().map(node -> node.cloneInto(root)).toList());
                storage.detachAndDelete();
                DeferredCaptureAllocator.coalesce(tree, parser);
                tree.parseAndInjectNode(parser, ASTInjectionPoint.END, "void main(){"
                        + "_flw_farDeferredPackMain(); if(gl_HelperInvocation)return;"
                        + coordinates
                        + "if(_flw_farDeferredHidden(view,hostDepth))return;"
                        + capture + "}");
            } else {
                if (frontLocation >= 0)
                    tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                            "layout(location=" + frontLocation + ") out float _flw_farPublishedFront;");
                tree.parseAndInjectNode(parser, ASTInjectionPoint.END, "void main(){_flw_farDeferredPackMain();"
                        + coordinates + (frontLocation >= 0 ? "_flw_farPublishedFront=_flw_farDeferredCoord.z;" : "")
                        + (retained ? "gl_FragDepth=clamp(hostDepth,0.0,1.0);" : "") + "}");
            }
        });
    }

    /**
     * Window depth of the main projection's NDC depth {@code ndc} in the pack's depth convention.
     */
    static String canonicalDepth(boolean reversed, String ndc) {
        return reversed && RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? ndc : ndc + "*0.5+0.5";
    }

    private static String output(Map<Integer, String> outputs, int location) {
        String output = outputs.get(location);
        if (output == null) throw new UnsupportedOperationException("Deferred host output location " + location);
        return output;
    }

    private static String semantic(Map<Integer, String> outputs, String name, int location) {
        return outputs.values().stream().filter(value -> value.contains(name)).findFirst()
                      .orElseGet(() -> output(outputs, location));
    }

    private static final String VIEW = """
            uniform mat4 _flw_farDeferredInverseHost;
            uniform mat4 _flw_farDeferredHostProjection;
            uniform mat4 _flw_farDeferredMainFromHost;
            uniform mat4 _flw_farDeferredModelView;
            uniform vec2 _flw_farDeferredSize;
            uniform bool _flw_farDeferredHostZeroToOne;
            uniform bool _flw_farDeferredHostReverseZ;
            uniform float _flw_farDeferredRasterScale;
            uniform vec2 _flw_farDeferredMainOpaqueScale;
            uniform sampler2D _flw_farDeferredHostOpaque;
            uniform sampler2D _flw_farDeferredMainOpaque;
            vec4 _flw_farDeferredCoord;
            bool _flw_farDeferredHidden(vec4 view,float hostDepth) {
                vec2 uv=gl_FragCoord.xy/_flw_farDeferredSize;
                float host=texture(_flw_farDeferredHostOpaque,uv).r;
                if(host!=(_flw_farDeferredHostReverseZ?0.0:1.0)
                        && (_flw_farDeferredHostReverseZ ? host>hostDepth : host<hostDepth)) return true;
                float main=texture(_flw_farDeferredMainOpaque,uv*_flw_farDeferredMainOpaqueScale).r;
                return main!=0.0 && _FLW_MAIN_OPAQUE_HIDES;
            }
            """;
}
