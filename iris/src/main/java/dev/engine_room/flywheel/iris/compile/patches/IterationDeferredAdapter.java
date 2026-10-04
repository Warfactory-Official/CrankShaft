package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.ContractProgram;
import dev.engine_room.flywheel.iris.compile.GuestShaders;
import dev.engine_room.flywheel.iris.engine.DeferredReplayPlan;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

final class IterationDeferredAdapter implements DeferredOitAdapter {
    private static String guardWork(String source, boolean diffuse, boolean compute) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation(
                (tree, root) -> {
                    tree.getVersionStatement().version = Version.fromNumber(430);
                    if (!root.identifierIndex.has("flw_count"))
                        inject(tree, parser, DeferredOitProfile.resource("iteration_layers.glsl"));
                    if (!root.identifierIndex.has("flw_materialAt")) {
                        inject(
                                tree,
                                parser,
                                DeferredOitProfile.resource("iteration_materials.glsl"),
                                ASTInjectionPoint.END);
                        tree.parseAndInjectNode(
                                parser,
                                ASTInjectionPoint.BEFORE_DECLARATIONS,
                                "uint flw_materialAt(uint pixel, int layer);");
                    }
                    if (!root.identifierIndex.has("screenSize"))
                        tree.parseAndInjectNode(
                                parser,
                                ASTInjectionPoint.BEFORE_DECLARATIONS,
                                "uniform vec2 screenSize;");
                    tree.parseAndInjectNodes(
                            parser,
                            ASTInjectionPoint.BEFORE_DECLARATIONS,
                            "uniform int flw_oitFarLayer;",
                            "uniform int flw_oitLayerIndex;");
                    if (!root.identifierIndex.has("flw_oitReplaying"))
                        tree.parseAndInjectNode(
                                parser,
                                ASTInjectionPoint.BEFORE_DECLARATIONS,
                                "uniform bool flw_oitReplaying;");
                    String skip =
                            "flw_oitReplaying && flw_oitActive && flw_overflow==0u && flw_maxLayers>1u"
                                    + " && uint(flw_oitLayerIndex)!=flw_maxLayers-1u";
                    if (!compute) {
                        tree.parseAndInjectNode(
                                parser,
                                ASTInjectionPoint.BEFORE_DECLARATIONS,
                                "layout(r32ui,binding=7) uniform readonly uimage2D flw_layerNode;");
                        tree.getOneMainDefinitionBody()
                            .getStatements()
                            .addFirst(
                                    parser.parseStatement(
                                            root,
                                            "if("
                                                    + skip
                                                    + " && imageLoad(flw_layerNode,ivec2(gl_FragCoord.xy)).r==0u) discard;"));
                    }
                    if (diffuse)
                        tree.getOneMainDefinitionBody()
                            .getStatements()
                            .addFirst(
                                    parser.parseStatement(
                                            root,
                                            "if("
                                                    + skip
                                                    + " && (flw_tiles[flw_oitLayerIndex]&1u)==0u) "
                                                    + (compute ? "return;" : "discard;")));
                });
        return parser.transform(source);
    }

    private static String captureMaterial(String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename("main", "_flw_materialMain");
            boolean waterOutput = root.identifierIndex.has("framebuffer_gwater");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "vec2 _flw_waterDepth = vec2(0.0);");
            root.nodeIndex.getStream(io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember.class)
                          .filter(member -> member.getName().getName().equals("parallaxWaterDist")).toList()
                          .forEach(member -> member.getAncestor(
                                                           io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement.class)
                                                   .getStatements().add(parser.parseStatement(root,
                                          "_flw_waterDepth = vec2(parallaxWaterDist, waterDist);")));
            inject(tree, parser, DeferredOitProfile.resource("iteration_layers.glsl")
                    + DeferredOitProfile.resource("iteration_materials.glsl"));
            DeferredCaptureAllocator.coalesce(tree, parser);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { _flw_materialMain(); if (gl_HelperInvocation) return; ivec2 pixel = ivec2(gl_FragCoord.xy);"
                            + " if (gl_FragCoord.z < texelFetch(depthtex1, pixel, 0).x) flw_captureMaterial(pixel, gl_FragCoord.z,"
                            + "framebuffer_albedo, framebuffer_gtransData, framebuffer_gtransNormal,"
                            + (waterOutput ? "framebuffer_gwater" : "vec4(0.0)") + ", _flw_waterDepth, uint(screenSize.x)); }");
        });
        return parser.transform(source);
    }

    private static String capture(String source, boolean additive, boolean early) {
        String radiance = additive
                ? "vec4(guestEmission.rgb + guestDisplay.rgb * texelFetch(pixelData2D, ivec2(0), 0).x * 0.13, 0.0)"
                : "vec4(guestUnlit.rgb * guestUnlit.a, guestUnlit.a)";
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename("main", "_flw_lightMain");
            if (!root.identifierIndex.has("pixelData2D")) tree.parseAndInjectNode(parser,
                    ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform sampler2D pixelData2D;");
            tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "uniform sampler2D depthtex1;", "uniform vec2 screenSize;");
            inject(tree, parser, DeferredOitProfile.resource("iteration_layers.glsl"));
            DeferredCaptureAllocator.coalesce(tree, parser);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { _flw_lightMain(); if (gl_HelperInvocation) return; ivec2 pixel = ivec2(gl_FragCoord.xy);"
                            + (early ? "" : " if (gl_FragCoord.z < texelFetch(depthtex1, pixel, 0).x)")
                            + " flw_captureLight(pixel, gl_FragCoord.z, " + radiance + ", uint(screenSize.x)); }");
        });
        return parser.transform(source);
    }

    private static String resolve(String source, int index) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation(
                (tree, root) -> {
                    tree.getVersionStatement().version = Version.fromNumber(430);
                    if (root.identifierIndex.has("fsrPixelSize")
                            || root.identifierIndex.has("fsrRenderScale")
                            || root.identifierIndex.has("lodProjection0")) {
                        throw new UnsupportedOperationException(
                                "Iteration light layers require native resolution and depth");
                    }
                    inject(tree, parser, DeferredOitProfile.resource("iteration_layers.glsl"));
                    if (index == 50) {
                        tree.parseAndInjectNode(
                                parser,
                                ASTInjectionPoint.BEFORE_DECLARATIONS,
                                "uniform bool flw_oitReplaying;");
                        tree.parseAndInjectNode(
                                parser,
                                ASTInjectionPoint.BEFORE_DECLARATIONS,
                                "vec3 flw_resolveRefractedLight(vec3 background, vec2 coord, float back, vec4 plane);");
                        inject(
                                tree,
                                parser,
                                DeferredOitProfile.resource("iteration_refracted_light.glsl"),
                                ASTInjectionPoint.END);
                        var function =
                                root.nodeIndex
                                        .getStream(FunctionDefinition.class)
                                        .filter(
                                                definition ->
                                                        definition
                                                                .getFunctionPrototype()
                                                                .getName()
                                                                .getName()
                                                                .equals("GlassRefraction"))
                                        .findFirst()
                                        .orElseThrow();
                        var body = function.getBody().getStatements();
                        body.add(
                                body.size() - 1,
                                parser.parseStatement(
                                        root,
                                        """
                                                if (!flw_oitReplaying || GetTransMaterialID(texelCoord) < 100.0 || GetTransMaterialID(texelCoord) > 104.0) {
                                                    float back = texelFetch(depthtex1, ivec2(refractCoord * screenSize), 0).x;
                                                    if (mask.water + mask.stainedGlass > 0.5) {
                                                        vec3 planeNormal = mask.water > 0.5 ? vertexNormal : vertexNormal * mat3(gbufferModelViewInverse);
                                                        planeNormal *= dot(planeNormal, viewDir) < 0.0 ? -1.0 : 1.0;
                                                        color = flw_resolveRefractedLight(color, refractCoord, back,
                                                                vec4(planeNormal, -dot(planeNormal, viewPos)));
                                                    } else color = flw_resolveLight(color, ivec2(refractCoord * screenSize), depth, back, uint(screenSize.x));
                                                }
                                                """));
                    } else {
                        var statements = tree.getOneMainDefinitionBody().getStatements();
                        int unlit = -1;
                        for (int i = 0; i < statements.size(); i++) {
                            if (statements.get(i) instanceof DeclarationStatement declaration
                                    && declaration.getDeclaration()
                                    instanceof TypeAndInitDeclaration typed
                                    && typed.getMembers().stream()
                                            .anyMatch(
                                                    member ->
                                                            member.getName()
                                                                  .getName()
                                                                  .equals("unlit"))) {
                                unlit = i;
                                break;
                            }
                        }
                        if (unlit < 0)
                            throw new UnsupportedOperationException(
                                    "Iteration light merge changed");
                        var old = new ArrayList<>(statements.subList(unlit, unlit + 3));
                        String body =
                                old.stream()
                                   .map(ASTPrinter::printSimple)
                                   .collect(Collectors.joining());
                        old.forEach(Statement::detachAndDelete);
                        statements.add(
                                unlit,
                                parser.parseStatement(
                                        root,
                                        "if (!flw_oitActive || flw_overflow != 0u) {"
                                                + body
                                                + "} else {"
                                                + "color = flw_resolveLight(color, texelCoord, 0.0, texelFetch(depthtex0, texelCoord, 0).x, uint(screenSize.x)); }"));
                    }
                });
        return parser.transform(source);
    }

    private static void inject(TranslationUnit tree, ASTParser parser, String source) {
        inject(tree, parser, source, ASTInjectionPoint.BEFORE_DECLARATIONS);
    }

    private static void inject(
            TranslationUnit tree, ASTParser parser, String source, ASTInjectionPoint point) {
        var library =
                parser.parseTranslationUnit(
                        tree.getRoot(), "#version 430 compatibility\n" + source);
        tree.injectNodes(
                point,
                library.getChildren().stream()
                       .map(node -> node.cloneInto(tree.getRoot()))
                       .toList());
        library.detachAndDelete();
    }

    @Override
    public int[] storageBindings() {
        return new int[]{1, 2, 3, 4, 5, 6};
    }

    @Override
    public boolean capturesTerrain() {
        return true;
    }

    @Override
    public void stage(Path root, Set<String> present, Map<Path, String> overrides, List<AbsolutePackPath> added) {
        for (String path : present) {
            if (!path.endsWith("/gbuffers_water.fsh")) continue;
            String dir = path.substring(0, path.length() - "gbuffers_water.fsh".length());
            for (String program : List.of("begin99", "deferred98", "composite1", "composite2")) {
                AbsolutePackPath target = AbsolutePackPath.fromAbsolutePath(dir + program + ".csh");
                if (present.contains(target.getPathString()))
                    throw new IllegalStateException("Layer stage is occupied: " + target);
                String source = DeferredOitProfile.resource(switch (program) {
                    case "begin99" -> "iteration_layer_clear.comp";
                    case "deferred98" -> "iteration_opaque.comp";
                    case "composite2" -> "iteration_layer_finish.comp";
                    default -> "iteration_layer_sort.comp";
                });
                source = source.replace("_FLW_ITERATION_LAYERS", DeferredOitProfile.resource("iteration_layers.glsl"));
                source = source.replace("_FLW_ITERATION_MATERIALS",
                        DeferredOitProfile.resource("iteration_materials.glsl"));
                if (program.equals("composite1")) {
                    String sort = DeferredOitProfile.resource("layer_sort.glsl")
                                                    .replace("_FLW_SORT_PIXEL", "gl_GlobalInvocationID.xy")
                                                    .replace(
                                                            "flw_nodes[flw_node * 2u + 1u].w = floatBitsToUint(transparentDensity);",
                                                            "")
                                                    .replace("_FLW_VISIBILITY_BODY", "")
                                                    .replace(" * 2u]", "]");
                    source = source.replace("_FLW_LAYER_SORT", sort
                            .replace("_FLW_SORT_COUNTS_LAYERS", "false")
                            .replace("_FLW_SORT_LAYER_BIAS", "0u"));
                    source = source.replace("_FLW_MATERIAL_SORT", sort.replace("flw_heads", "flw_materialHeads")
                                                                      .replace("flw_layerBefore", "flw_materialBefore")
                                                                      .replace("_FLW_SORT_COUNTS_LAYERS", "true")
                                                                      .replace("_FLW_SORT_LAYER_BIAS", "1u"));
                }
                overrides.put(target.resolved(root), source);
                added.add(target);
            }
        }
    }

    @Override
    public String storageProperties() {
        return """
                
                bufferObject.1 = 4 true 1.0 1.0
                bufferObject.2 = 2097152
                bufferObject.3 = 16
                bufferObject.4 = 4 true 0.25 0.25
                bufferObject.5 = 32 true 1.0 1.0
                bufferObject.6 = 4 true 1.0 1.0
                """;
    }

    @Override
    public void rejected(ProgramSet programs) {
        programs.getCompute(ProgramArrayId.Begin)[99] = new ComputeSource[0];
        programs.getCompute(ProgramArrayId.Deferred)[98] = new ComputeSource[0];
        programs.getCompute(ProgramArrayId.Composite)[1] = new ComputeSource[0];
        programs.getCompute(ProgramArrayId.Composite)[2] = new ComputeSource[0];
    }

    @Override
    public Map<String, String> fragments(ProgramSet programs, Map<ContractProgram, ProgramSource> contracts) {
        Map<String, String> result = new HashMap<>();
        ProgramSource water = programs.get(ProgramId.Water).orElseThrow();
        result.put(water.getName(), captureMaterial(water.getFragmentSource().orElseThrow()));
        ProgramSource translucent = contracts.get(ContractProgram.GBUFFERS_TRANSLUCENT);
        result.put(translucent.getName(), captureMaterial(translucent.getFragmentSource().orElseThrow()));
        ProgramSource unlit = contracts.get(ContractProgram.GBUFFERS_UNLIT_TRANSLUCENT);
        result.put(unlit.getName(), capture(unlit.getFragmentSource().orElseThrow(), false, false));
        for (ContractProgram id : List.of(ContractProgram.GBUFFERS_ADDITIVE,
                ContractProgram.GBUFFERS_NATIVE_ADDITIVE, ContractProgram.GBUFFERS_NATIVE_ADDITIVE_COLOR)) {
            ProgramSource source = contracts.get(id);
            result.put(source.getName(), capture(source.getFragmentSource().orElseThrow(), true,
                    id != ContractProgram.GBUFFERS_ADDITIVE));
        }
        for (int index : new int[]{50, 51}) {
            ProgramSource source = programs.getComposite(ProgramArrayId.Composite)[index];
            if (source.getDirectives().getViewportScale().scale() != 1) {
                throw new UnsupportedOperationException("Iteration light layers require the full-resolution viewport");
            }
            result.put(source.getName(), resolve(source.getFragmentSource().orElseThrow(), index));
        }
        for (int index = 5; index <= 50; index++) {
            ProgramSource source = programs.getComposite(ProgramArrayId.Composite)[index];
            if (source != null && source.isValid())
                result.put(
                        source.getName(),
                        guardWork(
                                result.getOrDefault(
                                        source.getName(), source.getFragmentSource().orElseThrow()),
                                index <= 25,
                                false));
            for (ComputeSource compute : programs.getCompute(ProgramArrayId.Composite)[index]) {
                if (compute != null && compute.isValid())
                    result.put(
                            compute.getName() + ".csh",
                            guardWork(compute.getSource().orElseThrow(), index <= 25, true));
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public @Nullable DeferredReplayPlan replayPlan(IrisRenderingPipeline pipeline, List<?> passes) {
        return IterationReplayResources.plan(pipeline, passes);
    }
}
