package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.ForwardOit;
import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.engine_room.flywheel.iris.compile.patches.ShaderDeclarations.uniform;

/**
 * Source-checked material variants; only owned shader code is staged, with native libraries read from the pack.
 */
public final class ForwardOitShaders {
    private static final String MATERIAL = "/crankshaft/forward_material.glsl";
    private static final String WATER_FOG = "_flw_forwardNativeWaterFog";

    private ForwardOitShaders() {
    }

    public static String properties(String source, DeferredOitProfile profile) {
        if (profile == DeferredOitProfile.SUNDIAL) source = source.replaceAll(
                "(?m)^(\\h*)texture\\.composite\\.colortex3(\\h*=.*)$",
                "$0\n$1texture.prepare._flw_forwardAtmosphere$2");
        return source + "\nsize.buffer.colortex15 = 2 1\nblend.prepare99 = off\n";
    }

    /**
     * {@code reversed}: the pack takes Minecraft's raw reversed depth and projections natively.
     */
    public static void stage(Path root, Set<String> present, Map<Path, String> overrides,
                             List<AbsolutePackPath> added, DeferredOitProfile profile, boolean reversed) {
        var material = AbsolutePackPath.fromAbsolutePath(MATERIAL);
        String materialSource = DeferredOitProfile.resource("forward_material.glsl");
        materialSource = switch (profile) {
            case SEUS, SEUS_HRR3, SEUS_GFME ->
                    DeferredOitProfile.resource("forward_seus_glass.glsl") + '\n' + materialSource;
            case ITERATION -> materialSource + '\n' + DeferredOitProfile.resource("forward_iteration_glass.glsl");
            default -> materialSource;
        };
        if (profile == DeferredOitProfile.SUNDIAL)
            materialSource = "#include \"/settings/VolumetricLightSettings.glsl\"\n" + materialSource;
        overrides.put(material.resolved(root), materialSource);
        added.add(material);
        for (String path : present) {
            if (!path.endsWith("/gbuffers_water.fsh")) continue;
            String dir = path.substring(0, path.length() - "gbuffers_water.fsh".length());
            var defines = new ArrayList<>(List.of("FLW_FORWARD_" + family(profile)));
            if (profile == DeferredOitProfile.SEUS_GFME) defines.add("FLW_FORWARD_GFME");
            if (dir.equals("/") || dir.equals("/world0/")) defines.add("FLW_FORWARD_OVERWORLD");
            if (reversed) defines.add("FLW_FORWARD_REVERSED_DEPTH");
            String light = profile == DeferredOitProfile.SUNDIAL ? "composite1"
                    : profile == DeferredOitProfile.ITERATION ? "composite5" : "composite4";
            String lightVertex = source(root, dir + light + ".vsh", overrides);
            if (lightVertex == null) lightVertex = source(root, "/" + light + ".vsh", overrides);
            var lightDefines = new ArrayList<>(defines);
            lightDefines.add("main _flw_forwardLightMain");
            if (profile == DeferredOitProfile.SUNDIAL)
                lightDefines.add("colortex3 _flw_forwardAtmosphere");
            lightVertex = ContractPatches.withDefines(lightVertex, lightDefines)
                    + "\n#undef main\n" + DeferredOitProfile.resource("forward_lighting.vert");
            if (present.contains(dir + "prepare99.vsh") || present.contains(dir + "prepare99.fsh"))
                throw new UnsupportedOperationException("Forward lighting cache program prepare99 is occupied");
            put(root, dir + "prepare99.vsh", lightVertex, overrides, added);
            put(root, dir + "prepare99.fsh", ContractPatches.withDefines(
                    DeferredOitProfile.resource("forward_lighting.frag"), defines), overrides, added);
            for (String program : List.of("gbuffers_water", "clrwl_gbuffers_translucent",
                    "clrwl_gbuffers_unlit_translucent")) {
                String original = program.endsWith("unlit_translucent") ? "clrwl_gbuffers_translucent" : program;
                var local = new ArrayList<>(defines);
                if (program.endsWith("unlit_translucent")) local.add("FLW_FORWARD_UNLIT");
                for (String stage : List.of(".vsh", ".fsh")) {
                    String input = source(root, dir + original + stage, overrides);
                    if (input == null) continue;
                    if (stage.equals(".fsh")) input = ContractPatches.withDefines(input, local)
                            + "\n#include \"" + MATERIAL + "\"\n";
                    put(root, dir + ForwardOit.PREFIX + program + stage, input, overrides, added);
                }
            }
        }
    }

    private static String family(DeferredOitProfile profile) {
        return switch (profile) {
            case SUNDIAL -> "SUNDIAL";
            case ITERATION -> "ITERATION";
            case SEUS, SEUS_HRR3, SEUS_GFME -> "SEUS";
        };
    }

    private static String source(Path root, String name, Map<Path, String> overrides) {
        return ContractPatches.staged(root, AbsolutePackPath.fromAbsolutePath(name), overrides);
    }

    private static void put(Path root, String name, String value, Map<Path, String> overrides,
                            List<AbsolutePackPath> added) {
        var path = AbsolutePackPath.fromAbsolutePath(name);
        overrides.put(path.resolved(root), value);
        added.add(path);
    }

    public static String material(String source, DeferredOitProfile profile, ProgramSet programs, int waterOutputs) {
        var demoted = GuestShaders.demoteForOit(source);
        source = demoted.source();
        var nativeOutputs = new HashMap<>(demoted.outputs());
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            root.rename("_flw_irisMain", "main");
            if (root.identifierIndex.has("gl_FragData")) {
                root.rename("gl_FragData", "_flw_forwardDiscarded");
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                        "vec4 _flw_forwardDiscarded[8];");
                for (int i = 0; i < waterOutputs; i++) nativeOutputs.put(i, "_flw_forwardDiscarded[" + i + ']');
            }
            if (root.identifierIndex.has("gl_FragColor")) {
                root.rename("gl_FragColor", "_flw_forwardDiscardedColor");
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                        "vec4 _flw_forwardDiscardedColor;");
                nativeOutputs.put(0, "_flw_forwardDiscardedColor");
            }
            if (profile == DeferredOitProfile.ITERATION && root.identifierIndex.has("FsrDiscardFS"))
                throw new UnsupportedOperationException(
                        "Forward IterationRP requires the pack's native render resolution");
            tree.getVersionStatement().version = Version.fromNumber(430);
            uniform(tree, root, parser, "sampler2D", "colortex15");
            uniform(tree, root, parser, "mat4", "gbufferProjectionInverse");
            uniform(tree, root, parser, "mat4", "gbufferModelViewInverse");
            uniform(tree, root, parser, "vec3", "shadowLightPosition");
            uniform(tree, root, parser, "vec3", "sunPosition");
            uniform(tree, root, parser, "sampler2D", "depthtex1");
            var functions = new ArrayList<String>();
            if (root.identifierIndex.has("_flw_forwardShadowPosition")) {
                uniform(tree, root, parser, profile == DeferredOitProfile.ITERATION ? "sampler2D" : "sampler2DShadow",
                        "shadowtex0");
                if (profile == DeferredOitProfile.ITERATION) {
                    functions.addAll(List.of("ShadowScreenPos_From_WorldPos", "DistortShadowScreenPos"));
                } else if (profile == DeferredOitProfile.SEUS_GFME) {
                    functions.add("WorldPosToShadowProjPos");
                } else if (profile == DeferredOitProfile.SUNDIAL) {
                    functions.add("worldPosToShadowCoord");
                }
            }
            if (profile == DeferredOitProfile.SUNDIAL) functions.add("waterFogScattering");
            boolean iterationFog = profile == DeferredOitProfile.ITERATION
                    && root.identifierIndex.has("_flw_forwardIterationFog");
            if (iterationFog) functions.add("WaterFog");
            if (!functions.isEmpty()) {
                int donor = switch (profile) {
                    case SUNDIAL -> 1;
                    case ITERATION -> 50;
                    case SEUS_GFME -> 4;
                    default -> throw new IllegalStateException("Unexpected native helper donor " + profile);
                };
                importFunctions(tree, root, parser,
                        programs.getComposite(ProgramArrayId.Composite)[donor].getFragmentSource().orElseThrow(),
                        functions,
                        profile == DeferredOitProfile.ITERATION ? Map.of("WaterFog", WATER_FOG) : Map.of());
            }
            if (iterationFog) {
                int parameters = root.nodeIndex.getStream(FunctionDefinition.class)
                                               .filter(function -> function.getFunctionPrototype().getName().getName()
                                                                           .equals(WATER_FOG))
                                               .findFirst().orElseThrow().getFunctionPrototype().getParameters().size();
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS,
                        "void _flw_forwardIterationFog(inout vec3 color, vec3 direction, float distance, vec3 normal, float vdot, float lightmap) { "
                                + WATER_FOG + "(color, direction, distance, " + (parameters == 7 ? "normal, vdot, false, lightmap" : "vdot") + "); }");
            }
            if (profile == DeferredOitProfile.SUNDIAL)
                SundialForwardTint.inject(tree, root, parser,
                        programs.getComposite(ProgramArrayId.Composite)[1].getFragmentSource().orElseThrow());
            if (profile == DeferredOitProfile.SEUS || profile == DeferredOitProfile.SEUS_HRR3
                    || profile == DeferredOitProfile.SEUS_GFME)
                SeusGlassShaders.inject(tree, root, parser,
                        programs.getComposite(ProgramArrayId.Composite)[profile == DeferredOitProfile.SEUS_GFME ? 1 : 0]
                                .getFragmentSource().orElseThrow());
            if (profile == DeferredOitProfile.ITERATION) {
                for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                    if (call.getFunctionName() == null) continue;
                    String name = call.getFunctionName().getName();
                    if (name.equals("imageAtomicMin")) {
                        var statement = call.getAncestor(ExpressionStatement.class);
                        var guarded = parser.parseStatement(root, "if (_flw_forwardEvaluate) {}");
                        var body = root.nodeIndex.getStream(CompoundStatement.class)
                                                 .filter(block -> block.hasAncestor(guarded)).findFirst().orElseThrow();
                        body.getStatements().add(statement.cloneInto(root));
                        statement.replaceByAndDelete(guarded);
                    } else if (name.equals("clrwl_computeFragment")) {
                        after(call.getAncestor(ExpressionStatement.class), parser, root,
                                "_flw_forwardTorchLight = " + ASTPrinter.printSimple(
                                        call.getParameters().get(2)) + ".x;");
                    }
                }
                for (var assignment : root.nodeIndex.getStream(AssignmentExpression.class).toList()) {
                    if (!ASTPrinter.printSimple(assignment.getLeft()).strip().equals("albedo.a")) continue;
                    before(assignment.getAncestor(ExpressionStatement.class), parser, root,
                            "_flw_forwardAlpha = albedo.a;");
                }
                tree.getOneMainDefinitionBody().getStatements().add(parser.parseStatement(root, """
                        if (_flw_forwardShadeMaterial) _flw_forwardColor = _flw_forwardIteration(vec4(albedo.rgb, _flw_forwardAlpha), v_worldPos,
                            waterNormal, vec2(_flw_forwardTorchLight, blockLight.y), specularTex.r,
                            specularTex.a, v_materialIDs, framebuffer_gtransData);
                        """));
            } else {
                String encode = profile == DeferredOitProfile.SUNDIAL ? "packUpGbufferDataSolid"
                        : "OutputGBufferDataTransparent";
                var call = root.nodeIndex.getStream(FunctionCallExpression.class)
                                         .filter(value -> value.getFunctionName() != null && value.getFunctionName()
                                                                                                  .getName()
                                                                                                  .equals(encode))
                                         .filter(value -> value.hasAncestor(tree.getOneMainDefinitionBody()))
                                         .findFirst().orElseThrow();
                String material = ASTPrinter.printSimple(call.getParameters().getFirst());
                before(call.getAncestor(ExpressionStatement.class), parser, root,
                        "if (_flw_forwardShadeMaterial) _flw_forwardColor = "
                                + (profile == DeferredOitProfile.SUNDIAL ? "_flw_forwardSundial(" + material + ", viewPos);"
                                : "_flw_forwardSeus(" + material + ", "
                                + (profile == DeferredOitProfile.SEUS_HRR3 ? "preDownscaleProjPos" : "viewPosition.xyz") + ");"));
            }
            root.rename("main", "_flw_forwardMaterialMain");
            var originalMain = root.nodeIndex.getStream(FunctionDefinition.class).filter(function -> function
                                           .getFunctionPrototype().getName().getName().equals("_flw_forwardMaterialMain")).findFirst()
                                             .orElseThrow();
            var movedMain = originalMain.cloneInto(root);
            originalMain.detachAndDelete();
            tree.getChildren().add(movedMain);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "layout(location = 0) out vec4 _flw_forwardOutput;");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "layout(location = 1) out vec4 _flw_forwardTransmission;");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "vec4 _flw_forwardNative[" + waterOutputs + "];");
            var capture = new StringBuilder();
            for (int i = 0; i < waterOutputs; i++)
                capture.append("_flw_forwardNative[").append(i)
                       .append("] = ").append(nativeOutputs.get(i)).append("; ");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END, "void main() { "
                    + (profile == DeferredOitProfile.ITERATION
                    ? "_flw_forwardAlpha = 1.0; _flw_forwardTorchLight = v_blockLight.x; " : "")
                    + "_flw_forwardColor = vec4(0.0); _flw_forwardLogTint = vec3(0.0); _flw_forwardWaterSurface = false; "
                    + "_flw_forwardMaterialMain(); "
                    + capture
                    + "if (_flw_forwardEvaluate && _flw_forwardShadeMaterial) _flw_forwardColor.rgb += _flw_forwardLightingContribution(); "
                    + "_flw_forwardOutput = _flw_forwardColor; "
                    + "_flw_forwardTransmission = vec4(_flw_forwardLogTint, 0.0); }");
        });
        return parser.transform(source);
    }

    private static void before(Statement statement, ASTParser parser,
                               Root root, String source) {
        var block = statement.getAncestor(CompoundStatement.class);
        block.getStatements().add(block.getStatements().indexOf(statement), parser.parseStatement(root, source));
    }

    private static void after(Statement statement, ASTParser parser,
                              Root root, String source) {
        var block = statement.getAncestor(CompoundStatement.class);
        block.getStatements().add(block.getStatements().indexOf(statement) + 1, parser.parseStatement(root, source));
    }

    private static void importFunctions(TranslationUnit target, Root root, ASTParser parser,
                                        String source, List<String> functions, Map<String, String> aliases) {
        var renames = new LinkedHashMap<>(aliases);
        renames.put("skyColorUp", "_flw_forwardNativeSky");
        renames.put("colorShadowlight", "_flw_forwardNativeSun");
        renames.put("colorSkylight", "_flw_forwardNativeSky");
        PackFunctions.copy(target, root, parser, source,
                functions.stream().map(name -> aliases.getOrDefault(name, name)).toList(), renames, null, true,
                (library, imports) -> {
                    if (!root.identifierIndex.has("_flw_forwardSundialVolume")) return;
                    var begin = library.getRoot().nodeIndex.getStream(TypeAndInitDeclaration.class)
                                                           .filter(declaration -> declaration.getMembers().stream()
                                                                                             .anyMatch(
                                                                                                     member -> member.getName()
                                                                                                                     .getName()
                                                                                                                     .equals("basicWeight")))
                                                           .findFirst().orElseThrow();
                    var block = begin.getAncestor(CompoundStatement.class);
                    var statements = block.getStatements();
                    int first = 0;
                    while (!begin.hasAncestor(statements.get(first))) first++;
                    var volume = root.nodeIndex.getStream(FunctionDefinition.class)
                                               .filter(function -> function.getFunctionPrototype().getName().getName()
                                                                           .equals("_flw_forwardSundialVolume"))
                                               .findFirst().orElseThrow().getBody();
                    // Reuse the native integration segment before its final colour write.
                    for (int i = first; i < statements.size() - 1; i++) {
                        var statement = statements.get(i);
                        imports.dependencies(statement);
                        volume.getStatements().add(volume.getStatements().size() - 2, statement.cloneInto(root));
                    }
                });
    }
}
