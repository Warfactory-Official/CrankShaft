package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.ContractProgram;
import dev.engine_room.flywheel.iris.compile.GuestShaders;
import dev.engine_room.flywheel.iris.engine.DeferredOitPool;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static dev.engine_room.flywheel.iris.compile.patches.ShaderDeclarations.uniform;

final class IterationDeferredAdapter implements DeferredOitAdapter {
    private static final DeferredOitPool.Spec POOL = new DeferredOitPool.Spec(2, 3, 7, 16, 1 << 16, 1 << 18, 16);

    private static String captureMaterial(String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename("main", "_flw_materialMain");
            uniform(tree, root, parser, "vec2", "screenSize");
            boolean waterOutput = root.identifierIndex.has("framebuffer_gwater");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "vec2 _flw_waterDepth = vec2(0.0);");
            root.nodeIndex.getStream(DeclarationMember.class)
                          .filter(member -> member.getName().getName().equals("parallaxWaterDist")).toList()
                          .forEach(member -> member.getAncestor(
                                                           CompoundStatement.class)
                                                   .getStatements().add(parser.parseStatement(root,
                                          "_flw_waterDepth = vec2(parallaxWaterDist, waterDist);")));
            inject(tree, parser, DeferredOitProfile.resource("iteration_layers.glsl")
                    + DeferredOitProfile.resource("iteration_materials.glsl"));
            inject(tree, parser, DeferredOitProfile.snapshot(POOL.snapshotBinding()), ASTInjectionPoint.END);
            DeferredCaptureAllocator.coalesce(tree, parser);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { _flw_materialMain(); if (gl_HelperInvocation) return; ivec2 pixel = ivec2(gl_FragCoord.xy);"
                            + " float flw_limit = flw_captureLimit(pixel, texelFetch(depthtex1, pixel, 0).x);"
                            + " if (_FLW_REVERSED_DEPTH ? gl_FragCoord.z > flw_limit : gl_FragCoord.z < flw_limit)"
                            + " flw_captureMaterial(pixel, gl_FragCoord.z,"
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
            DeferredSources.earlyFragmentTests(tree, root, parser);
            if (!root.identifierIndex.has("pixelData2D")) tree.parseAndInjectNode(parser,
                    ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform sampler2D pixelData2D;");
            uniform(tree, root, parser, "sampler2D", "depthtex1");
            uniform(tree, root, parser, "vec2", "screenSize");
            boolean interlock = additive && !early && DeferredLightDemand.simpleInterlock();
            String layers = DeferredOitProfile.resource("iteration_layers.glsl");
            inject(tree, parser, layers.replace("buffer FlwHeads",
                    interlock ? "coherent buffer FlwHeads" : "buffer FlwHeads"));
            inject(tree, parser, DeferredOitProfile.snapshot(POOL.snapshotBinding()), ASTInjectionPoint.END);
            if (!early) inject(tree, parser, DeferredOitProfile.CAPTURE_TEST, ASTInjectionPoint.END);
            if (additive) inject(tree, parser, "const int flw_lightPhase = 0;", ASTInjectionPoint.BEFORE_DECLARATIONS);
            if (interlock) DeferredLightDemand.interlock(tree, root, parser);
            DeferredCaptureAllocator.coalesce(tree, parser);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    DeferredLightDemand.captureMain(additive, early, radiance, interlock));
        });
        return parser.transform(source);
    }

    /**
     * Light-only surfaces in front of the nearest surface; the translucent pass composes those behind it.
     * {@code reversed}: the pack's sources are spelled in raw reversed depth.
     */
    private static String resolve(String source, boolean reversed) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            tree.getVersionStatement().version = Version.fromNumber(430);
            if (root.identifierIndex.has("fsrPixelSize") || root.identifierIndex.has("fsrRenderScale"))
                throw new UnsupportedOperationException("Iteration light layers require native resolution");
            var lod = IterationDeferredPatch.lodRemap(DeferredSources.function(root, "main"), root, "depth",
                    reversed);
            String front = "texelFetch(depthtex0, texelCoord, 0).x";
            if (lod != null) {
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                        IterationDeferredPatch.LOD_BOUND_PROTOTYPE);
                tree.parseAndInjectNode(parser, ASTInjectionPoint.END, IterationDeferredPatch.LOD_BOUND);
                front = "flw_lodBound(" + front + ", texelFetch(" + lod.sampler() + ", texelCoord, 0).x)";
            }
            uniform(tree, root, parser, "sampler2D", "pixelData2D");
            inject(tree, parser,
                    DeferredOitProfile.independentNodeAccess(DeferredOitProfile.resource("iteration_layers.glsl"))
                            + DeferredOitProfile.resource("iteration_light_resolve.glsl"));
            var statements = tree.getOneMainDefinitionBody().getStatements();
            int unlit = -1;
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i) instanceof DeclarationStatement declaration
                        && declaration.getDeclaration() instanceof TypeAndInitDeclaration typed
                        && typed.getMembers().stream().anyMatch(member -> member.getName().getName().equals("unlit"))) {
                    unlit = i;
                    break;
                }
            }
            if (unlit < 0) throw new UnsupportedOperationException("Iteration light merge changed");
            var old = new ArrayList<>(statements.subList(unlit, unlit + 3));
            String body = old.stream().map(ASTPrinter::printSimple).collect(Collectors.joining());
            old.forEach(Statement::detachAndDelete);
            String resolve = "if (!flw_oitActive || flw_overflow != 0u || !flw_ownLightSplit(texelCoord, uvec2(screenSize))) {" + body
                    + "} else {color = flw_resolveLight(color, texelCoord, _FLW_REVERSED_DEPTH ? 1.0 : 0.0, " + front
                    + ", uvec2(screenSize)); }";
            if (SortedBillboardBlend.enabled()) {
                uniform(tree, root, parser, "sampler2D", "colortex19");
                inject(tree, parser, DeferredOitProfile.resource("sorted_billboard_blend.glsl"));
                resolve = "if (flw_sortedBlendActive != 0u) {"
                        + " vec4 flw_sorted = texelFetch(colortex19, texelCoord, 0);"
                        + " if (flw_sorted.a != 1.0 || any(notEqual(flw_sorted.rgb, vec3(0.0))))"
                        + " color = flw_iterationCompositeDisplay(flw_sorted.rgb, flw_sorted.a, color);"
                        + " } else {" + resolve + "}";
            }
            statements.add(unlit, parser.parseStatement(root, resolve));
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
        return SortedBillboardBlend.enabled() ? new int[]{1, 2, 3, 6, 7, 8} : new int[]{1, 2, 3, 6, 7};
    }

    @Override
    public boolean capturesTerrain() {
        return true;
    }

    @Override
    public void stage(Path root, Set<String> present, Map<Path, String> overrides, List<AbsolutePackPath> added) {
        for (String path : present) {
            if (SortedBillboardBlend.enabled() && path.endsWith("/composite51.fsh")) {
                var composite = AbsolutePackPath.fromAbsolutePath(path);
                overrides.put(composite.resolved(root), ContractPatches.staged(root, composite, overrides) + '\n'
                        + DeferredOitProfile.resource("sorted_billboard_targets.glsl"));
            }
            if (path.endsWith("/composite42.fsh")) {
                var specular = AbsolutePackPath.fromAbsolutePath(path);
                overrides.put(specular.resolved(root), ContractPatches.staged(root, specular, overrides)
                                                                      .concat("\n#include \"/flywheel/iteration_display.glsl\"\n"));
            }
            if (!path.endsWith("/gbuffers_water.fsh")) continue;
            String dir = path.substring(0, path.length() - "gbuffers_water.fsh".length());
            // Placeholders for the layer programs, generated once the pack's depth convention is known, and for
            // IterationRearReflection, generated from the specular pass.
            for (String program : List.of("begin99", "composite1", "composite49", "composite49_a")) {
                AbsolutePackPath target = AbsolutePackPath.fromAbsolutePath(dir + program + ".csh");
                if (present.contains(target.getPathString()))
                    throw new IllegalStateException("Layer stage is occupied: " + target);
                overrides.put(target.resolved(root), DeferredOitProfile.resource("layer_rear_pass.comp"));
                added.add(target);
            }
        }
    }

    private static String layerProgram(String name) {
        return DeferredOitProfile.resource(name)
                                 .replace("_FLW_SORTED_BLEND_CLEAR_DECLARATION", SortedBillboardBlend.enabled()
                                         ? "layout(std430, binding = 8) buffer FlwSortedBlend { uint flw_sortedBlendActive; };" : "")
                                 .replace("_FLW_SORTED_BLEND_CLEAR", SortedBillboardBlend.enabled()
                                         ? "if (all(equal(gl_GlobalInvocationID.xy, uvec2(0u)))) flw_sortedBlendActive = 0u;" : "")
                                 .replace("_FLW_ITERATION_LAYERS", DeferredOitProfile.resource("iteration_layers.glsl"))
                                 .replace("_FLW_SNAPSHOT_BINDING", Integer.toString(POOL.snapshotBinding()))
                                 .replace("_FLW_ITERATION_MATERIALS",
                                         DeferredOitProfile.resource("iteration_materials.glsl"));
    }

    private static String sortCompute() {
        String source = layerProgram("iteration_layer_sort.comp");
        boolean ordered = OrderedBillboardCapture.enabled();
        source = source.replace("_FLW_ORDERED_LIGHT_SORT_LIBRARY", ordered
                ? DeferredOitProfile.resource("ordered_light_sort.glsl")
                + DeferredOitProfile.resource("iteration_light_sort.glsl") : "");
        if (ordered) source = source.replace("_FLW_LAYER_SORT", "flw_sortLight(gl_GlobalInvocationID.xy);");
        String lists = DeferredOitProfile.resource("layer_sort.glsl")
                                         .replace("_FLW_SORT_PIXEL", "gl_GlobalInvocationID.xy")
                                         .replace(
                                                 "flw_nodes[flw_node * 2u + 1u].w = floatBitsToUint(transparentDensity);",
                                                 "")
                                         .replace(" * 2u]", "]");
        String sort = DeferredLightDemand.preparedMaterialSort(lists.replace("_FLW_VISIBILITY_BODY", ""));
        source = source.replace("_FLW_LAYER_SORT", lists
                .replace("if (flw_oitActive) {",
                        "if (flw_oitActive && !flw_lightSimpleInitialized(ivec2(gl_GlobalInvocationID.xy), uvec2(screenSize))) {")
                .replace("_FLW_VISIBILITY_BODY", "flw_lightAlpha |= flw_nodes[flw_node].w >> 16u;"
                        + " flw_lightSum += vec3(unpackHalf2x16(flw_nodes[flw_node].z),"
                        + " unpackHalf2x16(flw_nodes[flw_node].w).x);"
                        + " float flw_lightDepth = uintBitsToFloat(flw_nodes[flw_node].y);"
                        + " flw_lightNearest = _FLW_REVERSED_DEPTH ? max(flw_lightNearest, flw_lightDepth)"
                        + " : min(flw_lightNearest, flw_lightDepth);"
                        + " flw_lightFarthest = _FLW_REVERSED_DEPTH ? min(flw_lightFarthest, flw_lightDepth)"
                        + " : max(flw_lightFarthest, flw_lightDepth);")
                .replace("_FLW_SORT_COUNTS_LAYERS", "false")
                .replace("_FLW_SORT_LAYER_BIAS", "0u")
                .replace("uint flw_length = 0u;", "uint flw_length = 0u; uint flw_lightAlpha = 0u;"
                        + " vec3 flw_lightSum = vec3(0.0);"
                        + " float flw_lightNearest = _FLW_REVERSED_DEPTH ? 0.0 : 1.0;"
                        + " float flw_lightFarthest = _FLW_REVERSED_DEPTH ? 1.0 : 0.0;")
                .replace("if (flw_length > 0u) {", "if (flw_length > 0u && flw_lightAlpha == 0u) {"
                        + " uint flw_summary = flw_lightSummaryOffset(flw_pixel, uvec2(screenSize));"
                        + " uint flw_stride = uint(screenSize.x) * uint(screenSize.y);"
                        + " flw_heads[flw_summary] = floatBitsToUint(flw_lightSum.r);"
                        + " flw_heads[flw_summary + flw_stride] = floatBitsToUint(flw_lightSum.g);"
                        + " flw_heads[flw_summary + 2u * flw_stride] = floatBitsToUint(flw_lightSum.b);"
                        + " flw_heads[flw_summary + 3u * flw_stride] = floatBitsToUint(flw_lightNearest);"
                        + " flw_heads[flw_summary + 4u * flw_stride] = floatBitsToUint(flw_lightFarthest);"
                        + " flw_heads[flw_pixel] |= FLW_LIGHT_UNSORTED; } else if (flw_length > 0u) {"));
        // Rear layers resolve back to front: material lists run farthest first.
        source = source.replace("_FLW_MATERIAL_SORT", sort.replace("flw_heads", "flw_materialHeads")
                                                          .replace("flw_layerBefore(flw_left, flw_right)",
                                                                  "flw_materialBefore(flw_right, flw_left)")
                                                          .replace("_FLW_SORT_COUNTS_LAYERS", "true")
                                                          .replace("_FLW_SORT_LAYER_BIAS", "1u"));
        if (Boolean.parseBoolean(System.getProperty("crankshaft.iris.relaxedSortNodes", "true"))) {
            String declaration = "coherent buffer FlwNodes";
            if (!source.contains(declaration))
                throw new IllegalStateException("Iteration sort node declaration is missing");
            // Each pixel owns a disjoint list; the dispatch barrier publishes the reordered nodes.
            source = source.replace(declaration, "buffer FlwNodes");
        }
        return SortedBillboardBlend.enabled() ? SortedBillboardBlend.skipIfFast(source) : source;
    }

    @Override
    public String storageProperties() {
        return """
                
                bufferObject.1 = 28 true 1.0 1.0
                bufferObject.2 = 2097152
                bufferObject.3 = 144
                bufferObject.6 = 4 true 1.0 1.0
                bufferObject.7 = 4 true 1.0 1.0
                """ + (SortedBillboardBlend.enabled() ? "bufferObject.8 = 516\n" : "");
    }

    @Override
    public void rejected(ProgramSet programs) {
        programs.getCompute(ProgramArrayId.Begin)[99] = new ComputeSource[0];
        programs.getCompute(ProgramArrayId.Composite)[1] = new ComputeSource[0];
        programs.getCompute(ProgramArrayId.Composite)[49] = new ComputeSource[0];
    }

    @Override
    public Map<String, String> fragments(ProgramSet programs, Map<ContractProgram, ProgramSource> contracts) {
        boolean reversed = programs.getPack().flywheel$nativeDepth();
        Map<String, String> result = new HashMap<>();
        result.put("begin99.csh", layerProgram("iteration_layer_clear.comp"));
        result.put("composite1.csh", sortCompute());
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
        ProgramSource specular = programs.getComposite(ProgramArrayId.Composite)[42];
        if (specular == null) throw new UnsupportedOperationException("Iteration specular tracing pass is missing");
        for (int index : new int[]{50, 51}) {
            ProgramSource source = programs.getComposite(ProgramArrayId.Composite)[index];
            if (source.getDirectives().getViewportScale().scale() != 1) {
                throw new UnsupportedOperationException("Iteration light layers require the full-resolution viewport");
            }
            String fragment = source.getFragmentSource().orElseThrow();
            result.put(source.getName(), index == 50 ? IterationDeferredPatch.resolve(fragment, reversed)
                    : resolve(fragment, reversed));
        }
        String specularFragment = specular.getFragmentSource().orElseThrow();
        String specularVertex = specular.getVertexSource().orElseThrow();
        result.put("composite49.csh", IterationRearReflection.pass(specularFragment, specularVertex, true, reversed));
        result.put("composite49_a.csh",
                IterationRearReflection.pass(specularFragment, specularVertex, false, reversed));
        return Map.copyOf(result);
    }

    @Override
    public DeferredOitPool.Spec pool() {
        return POOL;
    }
}
