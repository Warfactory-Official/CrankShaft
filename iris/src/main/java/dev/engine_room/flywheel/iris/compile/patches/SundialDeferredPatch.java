package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.selection.SelectionStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static dev.engine_room.flywheel.iris.compile.patches.DeferredSources.*;

final class SundialDeferredPatch {
    private static final Pattern REFRACTED_READ = Pattern.compile("texelFetch\\(colortex5,\\s*(.+),\\s*0\\)\\.rgb");

    private SundialDeferredPatch() {
    }

    static String capture(String source, int snapshotBinding) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            for (String output : List.of("gbufferData0", "gbufferData1", "gbufferData2")) {
                if (!root.identifierIndex.has(output)) throw unsupported("missing output " + output);
            }
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename("main", "_flw_layerMain");
            inject(tree, parser, DeferredOitProfile.resource("layer_capture.glsl")
                                                   .replace("_FLW_CAPTURE_SNAPSHOT",
                                                           DeferredOitProfile.snapshot(snapshotBinding)),
                    ASTInjectionPoint.END);
            DeferredCaptureAllocator.coalesce(tree, parser);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { _flw_layerMain(); if (gl_HelperInvocation) return; flw_captureLayer(gbufferData0, gbufferData1, gbufferData2); }");
        });
        return parser.transform(source);
    }

    static String lightSort(String pixel) {
        return DeferredOitProfile.resource("layer_sort.glsl")
                                 .replace("if (flw_oitActive) {",
                                         "if (flw_oitActive && !flw_lightSimpleInitialized(ivec2("
                                                 + pixel + "), uvec2(screenSize))) {")
                                 .replace("_FLW_SORT_PIXEL", pixel)
                                 .replace("flw_heads", "flw_lightHeads")
                                 .replace("flw_layerBefore", "flw_lightBefore")
                                 .replace(" * 2u", "")
                                 .replace("_FLW_VISIBILITY_BODY", "flw_lightAlpha |= flw_nodes[flw_node].w >> 16u;"
                                         + " flw_lightSum += vec3(unpackHalf2x16(flw_nodes[flw_node].z),"
                                         + " unpackHalf2x16(flw_nodes[flw_node].w).x);"
                                         + " float flw_lightDepth = uintBitsToFloat(flw_nodes[flw_node].y);"
                                         + " flw_lightNearest = _FLW_REVERSED_DEPTH ? max(flw_lightNearest, flw_lightDepth)"
                                         + " : min(flw_lightNearest, flw_lightDepth);"
                                         + " flw_lightFarthest = _FLW_REVERSED_DEPTH ? min(flw_lightFarthest, flw_lightDepth)"
                                         + " : max(flw_lightFarthest, flw_lightDepth);")
                                 .replace("flw_nodes[flw_node + 1u].w = floatBitsToUint(transparentDensity);", "")
                                 .replace("_FLW_SORT_COUNTS_LAYERS", "false")
                                 .replace("_FLW_SORT_LAYER_BIAS", "0u")
                                 .replace("uint flw_length = 0u;", "uint flw_length = 0u; uint flw_lightAlpha = 0u;"
                                         + " vec3 flw_lightSum = vec3(0.0);"
                                         + " float flw_lightNearest = _FLW_REVERSED_DEPTH ? 0.0 : 1.0;"
                                         + " float flw_lightFarthest = _FLW_REVERSED_DEPTH ? 1.0 : 0.0;")
                                 .replace("if (flw_length > 0u) {", "if (flw_length > 0u && flw_lightAlpha == 0u) {"
                                         + " uint flw_summary = flw_lightSummaryOffset(flw_pixel, uvec2(screenSize));"
                                         + " uint flw_stride = uint(screenSize.x) * uint(screenSize.y);"
                                         + " flw_lightHeads[flw_summary] = floatBitsToUint(flw_lightSum.r);"
                                         + " flw_lightHeads[flw_summary + flw_stride] = floatBitsToUint(flw_lightSum.g);"
                                         + " flw_lightHeads[flw_summary + 2u * flw_stride] = floatBitsToUint(flw_lightSum.b);"
                                         + " flw_lightHeads[flw_summary + 3u * flw_stride] = floatBitsToUint(flw_lightNearest);"
                                         + " flw_lightHeads[flw_summary + 4u * flw_stride] = floatBitsToUint(flw_lightFarthest);"
                                         + " flw_lightHeads[flw_pixel] |= FLW_LIGHT_UNSORTED; } else if (flw_length > 0u) {");
    }

    // Rear layers resolve back to front: material lists run farthest first.
    static String materialSort() {
        String sort = DeferredOitProfile.resource("layer_sort.glsl");
        String store = "flw_nodes[flw_node * 2u + 1u].w = floatBitsToUint(transparentDensity);";
        String order = "flw_layerBefore(flw_left, flw_right)";
        if (sort.indexOf(store) < 0 || sort.indexOf(store) != sort.lastIndexOf(store)
                || sort.indexOf(order) < 0 || sort.indexOf(order) != sort.lastIndexOf(order))
            throw unsupported("material sort");
        return DeferredLightDemand.preparedMaterialSort(sort.replace(store,
                                                                    "flw_nodes[flw_node * 2u + 1u].w = (floatBitsToUint(transparentDensity) & 0x7fffffffu)"
                                                                            + " | (flw_nodes[flw_node * 2u + 1u].w & FLW_LAYER_UNLIT);")
                                                            .replace(order, "flw_layerBefore(flw_right, flw_left)"));
    }

    static String captureLight(String source, boolean early, int snapshotBinding) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename("main", "_flw_lightMain");
            earlyFragmentTests(tree, root, parser);
            if (!root.identifierIndex.has("screenSize")) tree.parseAndInjectNode(parser,
                    ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform vec2 screenSize;");
            if (!root.identifierIndex.has("depthtex1")) tree.parseAndInjectNode(parser,
                    ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform sampler2D depthtex1;");
            boolean additive = !root.identifierIndex.has("guestUnlit");
            boolean interlock = additive && !early && DeferredLightDemand.simpleInterlock();
            inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl")
                            + DeferredOitProfile.resource("layer_light.glsl").replace("buffer FlwLightHeads",
                            interlock ? "coherent buffer FlwLightHeads" : "buffer FlwLightHeads"),
                    ASTInjectionPoint.BEFORE_DECLARATIONS);
            inject(tree, parser, DeferredOitProfile.snapshot(snapshotBinding), ASTInjectionPoint.END);
            if (!early) inject(tree, parser, DeferredOitProfile.CAPTURE_TEST, ASTInjectionPoint.END);
            DeferredCaptureAllocator.coalesce(tree, parser);
            if (additive) inject(tree, parser, "const int flw_lightPhase = 0;", ASTInjectionPoint.BEFORE_DECLARATIONS);
            if (interlock) DeferredLightDemand.interlock(tree, root, parser);
            String value = !additive
                    ? "vec4(guestUnlit.rgb * guestUnlit.a, guestUnlit.a)" : "vec4(guestLight.rgb, 0.0)";
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    DeferredLightDemand.captureMain(additive, early, value, interlock));
        });
        return parser.transform(source);
    }

    /**
     * {@code reflectionStage}: the pack's surface reflection pass, donor of the rear-layer reflection.
     */
    /**
     * {@code reversed}: the pack's sources are spelled in raw reversed depth.
     */
    static String composite(int index, String source, String reflectionStage, boolean reversed) {
        return transform(DeferredLodDepth.sundial(source, reversed), (tree, root, parser) -> {
            List<Statement> statements = tree.getOneMainDefinitionBody().getStatements();
            switch (index) {
                case 0 -> sort(tree, root, parser, statements);
                case 1 -> resolve(tree, root, parser, statements, reflectionStage, reversed);
                case 6 -> frontLight(tree, root, parser, statements);
                default -> throw new IllegalArgumentException("Sundial deferred stage composite" + index);
            }
        });
    }

    private static void sort(TranslationUnit tree, Root root, ASTParser parser, List<Statement> statements) {
        int first = declaration(statements, "transparentDensity");
        int last = assignment(statements, "texBuffer5");
        if (first < 0 || last <= first) throw unsupported("cloud visibility range");
        String visibility = print(statements.subList(first, last));
        var block = (CompoundStatement) parser.parseStatement(root, "{" + visibility + "}");
        int[] replaced = {0};
        block.getRoot().nodeIndex.getStream(DeclarationMember.class)
                                 .filter(member -> member.getName().getName()
                                                         .equals("waterDepth") && member.hasAncestor(block))
                                 .toList().forEach(member -> {
                 if (!(member.getInitializer() instanceof ExpressionInitializer initializer))
                     throw unsupported("cloud depth initializer");
                 initializer.setExpression(parser.parseExpression(root, "uintBitsToFloat(flw_nodes[flw_node].y)"));
                 replaced[0]++;
             });
        if (replaced[0] != 1) throw unsupported("cloud layer depth input");
        String perLayer = print(block.getStatements());
        block.detachAndDelete();
        inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl")
                + DeferredOitProfile.resource("layer_light.glsl"), ASTInjectionPoint.BEFORE_DECLARATIONS);
        statements.add(last, parser.parseStatement(root, materialSort().replace("_FLW_VISIBILITY_BODY", perLayer)
                                                                       .replace(" * 2u", "")
                                                                       .replace("_FLW_SORT_COUNTS_LAYERS", "true")
                                                                       .replace("_FLW_SORT_LAYER_BIAS", "0u")
                                                                       .replace("_FLW_SORT_PIXEL",
                                                                               "uvec2(gl_FragCoord.xy)")));
        statements.add(last + 1, parser.parseStatement(root, lightSort("uvec2(gl_FragCoord.xy)")));
    }

    /**
     * Composites every captured surface behind the native front surface into the background it reads, using this
     * pass's own body per rear surface. Refraction samples the composed background at its target.
     */
    private static void resolve(TranslationUnit tree, Root root, ASTParser parser, List<Statement> statements,
                                String reflectionStage, boolean reversed) {
        if (declaration(statements, "texel") != 0 || declaration(statements, "waterDepth") < 0
                || declaration(statements, "waterDepth") > declaration(statements, "solidDepth")
                || declaration(statements, "solidColor") < 0
                || declaration(statements, "absorption") <= declaration(statements, "solidColor"))
            throw unsupported("material body");
        var main = function(root, "main");
        if (!(member(main, root, "volumetricLight").getInitializer() instanceof ExpressionInitializer radiance)
                || !ASTPrinter.printSimple(radiance.getExpression()).contains("gbufferData.emissive"))
            throw unsupported("material radiance");
        radiance.setExpression(
                parser.parseExpression(root, "flw_layerUnlit ? gbufferData.albedo.rgb * gbufferData.albedo.w : ("
                        + ASTPrinter.printSimple(radiance.getExpression()) + ")"));
        for (var statement : root.nodeIndex.getStream(ExpressionStatement.class).toList()) {
            if (!statement.hasAncestor(main)
                    || !ASTPrinter.printSimple(statement).replaceAll("\\s+", "").equals("volumetricLight+=shadow;"))
                continue;
            statement.replaceByAndDelete(parser.parseStatement(root,
                    "if (!flw_layerUnlit) { " + ASTPrinter.printSimple(statement) + " }"));
        }

        inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl")
                + DeferredOitProfile.resource("layer_light.glsl"), ASTInjectionPoint.BEFORE_DECLARATIONS);
        tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "vec3 flw_resolveRefractedLight(vec3 background, vec2 coord, float back, vec4 plane);",
                "vec2 _flw_farTexcoord;", "vec4 _flw_farFragCoord;");
        boolean reflects = reflects(reflectionStage);
        decode(tree, root, parser);
        tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                "vec3 _flw_farLayer(uint flw_farNode, ivec2 texel, vec2 texcoord, vec3 flw_farBackground, float flw_farBack) {}");
        var far = (FunctionDefinition) tree.getChildren().getLast();
        far.getBody().replaceByAndDelete(main.getBody().cloneInto(root));
        farLayer(tree, far, reflects, reversed, root, parser);
        tree.parseAndInjectNode(parser, ASTInjectionPoint.END, "vec2 _flw_farRefraction(uint flw_farNode, ivec2 texel, "
                + "vec2 texcoord, float flw_farBack, out vec4 flw_farPlane) {}");
        var refraction = (FunctionDefinition) tree.getChildren().getLast();
        refraction.getBody().replaceByAndDelete(main.getBody().cloneInto(root));
        farRefraction(tree, refraction, root, parser);
        walker(tree, parser, "vec3", "screenToViewPos(coord, depth)", "flw_heads",
                DeferredLodDepth.opaque(root, "pixel"));
        pathWalker(tree, parser, "vec3", DeferredLodDepth.opaque(root, "pixel"), "texelFetch(colortex5, pixel, 0).rgb");
        tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "bool flw_farUnlit(ivec2 pixel, float front);");
        inject(tree, parser, DeferredOitProfile.resource("layer_deferred_unlit.glsl")
                + DeferredOitProfile.resource("layer_refracted_light.glsl"), ASTInjectionPoint.END);

        int solid = declaration(statements, "solidDepth");
        statements.addAll(solid + 1, List.of(
                parser.parseStatement(root,
                        "solidDepth = flw_farDepth(texel, waterDepth, vec4(0.0), false, solidDepth);"),
                parser.parseStatement(root, "bool flw_layerUnlit = flw_farUnlit(texel, waterDepth);"),
                parser.parseStatement(root, "ivec2 flw_farPixel = texel;"),
                parser.parseStatement(root, "bool flw_farPlanar = false;"),
                parser.parseStatement(root, "vec4 flw_farPlane = vec4(0.0);")));
        int direction = declaration(statements, "waterWorldDir");
        if (direction < 0) throw unsupported("view direction");
        statements.addAll(direction + 1, List.of(parser.parseStatement(root,
                        "vec3 flw_planeNormal = gbufferData.geoNormal * (dot(gbufferData.geoNormal, waterViewPos) < 0.0 ? -1.0 : 1.0);"),
                parser.parseStatement(root,
                        "flw_farPlane = vec4(flw_planeNormal, -dot(flw_planeNormal, waterViewPos));")));
        if (!(member(main, root, "targetSolidDepth").getInitializer() instanceof ExpressionInitializer targetDepth))
            throw unsupported("refraction depth");
        targetDepth.setExpression(parser.parseExpression(root,
                "flw_farDepth(ivec2(refractionTarget * screenSize), waterDepth, flw_farPlane, true, "
                        + ASTPrinter.printSimple(targetDepth.getExpression()) + ")"));
        int refracted = 0;
        for (var statement : root.nodeIndex.getStream(ExpressionStatement.class).toList()) {
            if (!statement.hasAncestor(main) || !(statement.getExpression() instanceof AssignmentExpression assignment)
                    || !ASTPrinter.printSimple(assignment.getLeft()).strip().equals("solidColor.rgb")) continue;
            var read = REFRACTED_READ.matcher(ASTPrinter.printSimple(assignment.getRight()).strip());
            if (!read.matches()) continue;
            var block = (CompoundStatement) statement.getParent();
            block.getStatements().add(block.getStatements().indexOf(statement) + 1, parser.parseStatement(root,
                    "{ flw_farPixel = " + read.group(1) + "; flw_farPlanar = true; }"));
            refracted++;
        }
        if (refracted != 1) throw unsupported("refracted background read");
        var statement = member(main, root, "waterDistance").getAncestor(DeclarationStatement.class);
        var block = (CompoundStatement) statement.getParent();
        block.getStatements().add(block.getStatements().indexOf(statement), parser.parseStatement(root, """
                solidColor.rgb = flw_farPlanar
                        ? flw_resolveRefractedLight(flw_farPath(flw_farPixel, waterDepth, flw_farPlane, true, solidColor.rgb),
                                (vec2(flw_farPixel) + 0.5) / screenSize, solidDepth, flw_farPlane)
                        : flw_resolveLight(flw_farPath(texel, waterDepth, flw_farPlane, false, solidColor.rgb),
                                texel, waterDepth, solidDepth, uvec2(screenSize));
                """));
    }

    /**
     * {@code _flw_farGbuffer}: the pass's G-buffer decode reading a captured surface's record.
     */
    static void decode(TranslationUnit tree, Root root, ASTParser parser) {
        var decode = graft(tree, root, parser, function(root, "getGbufferData"), "GbufferData", "_flw_farGbuffer",
                "uint flw_farNode, float flw_farBack");
        if (redirect(decode, root, parser, Map.of(
                "colortex0", "unpackUnorm4x8(flw_nodes[flw_farNode].z)",
                "colortex1",
                "vec4(unpackSnorm2x16(flw_nodes[flw_farNode].w), unpackSnorm2x16(flw_nodes[flw_farNode + 1u].x))",
                "colortex2",
                "vec4(unpackUnorm2x16(flw_nodes[flw_farNode + 1u].y), unpackUnorm2x16(flw_nodes[flw_farNode + 1u].z))",
                "depthtex1", "vec4(flw_farBack)")) != 4)
            throw unsupported("material decode reads");
    }

    /**
     * Whether the pack's options keep the reflection stage's surface reflection.
     */
    static boolean reflects(String reflectionStage) {
        boolean[] found = {false};
        transform(reflectionStage,
                (_, root, _) -> found[0] = !calls(function(root, "main"), root, "reflection").isEmpty());
        return found[0];
    }

    /**
     * Imports the reflection stage's surface reflection and everything it reaches; returns its guarded call, or null
     * when the pack's options disable reflections. Screen position reads become the rear surface's.
     */
    static @Nullable Statement reflection(TranslationUnit tree, Root root, ASTParser parser, String source) {
        Statement[] guard = {null};
        PackFunctions.copy(tree, root, parser, source, List.of(),
                Map.of("texcoord", "_flw_farTexcoord", "gl_FragCoord", "_flw_farFragCoord"), null, false,
                (library, importer) -> {
                    var main = function(library.getRoot(), "main");
                    var calls = calls(main, library.getRoot(), "reflection");
                    if (calls.isEmpty()) return;
                    var selection = calls.getFirst().getAncestor(SelectionStatement.class);
                    if (calls.size() != 1 || selection == null || !selection.hasAncestor(main))
                        throw unsupported("surface reflection call");
                    var outputs = outputs(library);
                    for (var statement : library.getRoot().nodeIndex.getStream(ExpressionStatement.class).toList())
                        if (statement.hasAncestor(selection) && outputs.contains(written(statement)))
                            statement.detachAndDelete();
                    importer.dependencies(selection);
                    guard[0] = selection.cloneInto(root);
                });
        return guard[0];
    }

    /**
     * This pass's body for one rear surface: no outputs, camera-side fog or refraction offset. Its reflection comes
     * from {@link SundialRearReflection}: mirror light and weight, and the filtered rough reflection.
     */
    private static void farLayer(TranslationUnit tree, FunctionDefinition far, boolean reflects, boolean reversed,
                                 Root root, ASTParser parser) {
        List<Statement> statements = far.getBody().getStatements();
        for (Statement statement : List.copyOf(statements.subList(declaration(statements, "absorption"),
                statements.size())))
            statement.detachAndDelete();
        statements.getFirst().detachAndDelete();
        initialize(far, root, parser, "gbufferData", "_flw_farGbuffer(texel, texcoord, flw_farNode, flw_farBack)");
        initialize(far, root, parser, "waterDepth", "uintBitsToFloat(flw_nodes[flw_farNode].y)");
        initialize(far, root, parser, "solidDepth", "flw_farBack");
        initialize(far, root, parser, "solidColor",
                "vec4(flw_farBackground, uintBitsToFloat(flw_nodes[flw_farNode + 1u].w & 0x7fffffffu))");
        int background = declaration(statements, "solidColor");
        statements.addAll(background + 1, List.of(
                parser.parseStatement(root,
                        "solidColor.rgb = flw_resolveLight(solidColor.rgb, texel, waterDepth, solidDepth, uvec2(screenSize));"),
                parser.parseStatement(root,
                        "bool flw_layerUnlit = (flw_nodes[flw_farNode + 1u].w & FLW_LAYER_UNLIT) != 0u;")));
        var outputs = outputs(tree);
        for (var statement : root.nodeIndex.getStream(ExpressionStatement.class).toList())
            if (statement.hasAncestor(far) && outputs.contains(written(statement))) statement.detachAndDelete();
        int refractions = 0;
        Class<? extends BinaryExpression> refraction = reversed ? GreaterThanExpression.class
                : LessThanExpression.class;
        for (var comparison : root.nodeIndex.getStream(refraction).toList()) {
            if (!comparison.hasAncestor(far)
                    || !ASTPrinter.printSimple(comparison.getLeft()).strip().equals("waterDepth")
                    || !ASTPrinter.printSimple(comparison.getRight()).strip().equals("targetSolidDepth")) continue;
            comparison.replaceByAndDelete(parser.parseExpression(root, "false"));
            refractions++;
        }
        if (refractions != 1) throw unsupported("rear refraction");
        for (var identifier : root.identifierIndex.getStream("gl_FragCoord").toList())
            if (identifier.hasAncestor(far)) identifier.setName("_flw_farFragCoord");
        statements.addAll(0, List.of(parser.parseStatement(root, "_flw_farTexcoord = texcoord;"),
                parser.parseStatement(root, "_flw_farFragCoord = vec4(vec2(texel) + 0.5, 0.0, 1.0);")));
        if (!reflects) {
            statements.add(parser.parseStatement(root, "return solidColor.rgb;"));
            return;
        }
        statements.add(parser.parseStatement(root, "return max(solidColor.rgb + " + SundialRearReflection.LIGHT
                + ", vec3(0.0)) + max(" + RearReflectionCompute.filtered("flw_farNode", SundialRearReflection.SLOT)
                + ".rgb, vec3(0.0)) * " + SundialRearReflection.WEIGHT + ";"));
    }

    /**
     * This pass's refraction target for one rear surface, ending at the pack's target computation; its plane.
     */
    private static void farRefraction(TranslationUnit tree, FunctionDefinition refraction, Root root,
                                      ASTParser parser) {
        List<Statement> statements = refraction.getBody().getStatements();
        statements.getFirst().detachAndDelete();
        initialize(refraction, root, parser, "gbufferData",
                "_flw_farGbuffer(texel, texcoord, flw_farNode, flw_farBack)");
        initialize(refraction, root, parser, "waterDepth", "uintBitsToFloat(flw_nodes[flw_farNode].y)");
        initialize(refraction, root, parser, "solidDepth", "flw_farBack");
        initialize(refraction, root, parser, "solidColor", "vec4(0.0)");
        var target = member(refraction, root, "refractionTarget").getAncestor(DeclarationStatement.class);
        truncate(refraction, target, false);
        ((CompoundStatement) target.getParent()).getStatements()
                                                .add(parser.parseStatement(root, "return refractionTarget;"));
        var outputs = outputs(tree);
        for (var statement : root.nodeIndex.getStream(ExpressionStatement.class).toList())
            if (statement.hasAncestor(refraction) && outputs.contains(written(statement))) statement.detachAndDelete();
        for (var identifier : root.identifierIndex.getStream("gl_FragCoord").toList())
            if (identifier.hasAncestor(refraction)) identifier.setName("_flw_farFragCoord");
        int direction = declaration(statements, "waterWorldDir");
        if (direction < 0) throw unsupported("rear view direction");
        statements.addAll(direction + 1, List.of(parser.parseStatement(root,
                        "vec3 flw_planeNormal = gbufferData.geoNormal * (dot(gbufferData.geoNormal, waterViewPos) < 0.0 ? -1.0 : 1.0);"),
                parser.parseStatement(root,
                        "flw_farPlane = vec4(flw_planeNormal, -dot(flw_planeNormal, waterViewPos));")));
        statements.addAll(0, List.of(parser.parseStatement(root, "_flw_farTexcoord = texcoord;"),
                parser.parseStatement(root, "_flw_farFragCoord = vec4(vec2(texel) + 0.5, 0.0, 1.0);")));
        statements.add(parser.parseStatement(root, "return texcoord;"));
    }

    /**
     * Light-only surfaces in front of the nearest material surface; resolve composes those behind it.
     */
    private static void frontLight(TranslationUnit tree, Root root, ASTParser parser, List<Statement> statements) {
        inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl")
                + DeferredOitProfile.resource("layer_light.glsl"), ASTInjectionPoint.BEFORE_DECLARATIONS);
        int first = declaration(statements, "_flw_guestLight");
        if (first < 0 || assignment(statements, "texBuffer5") <= first) throw unsupported("guest light range");
        if (declaration(statements, "_flw_guestUnlit") != first + 2) throw unsupported("unlit opacity merge");
        List<Statement> nativeLight = new ArrayList<>(statements.subList(first + 1, first + 4));
        String nativeMerge = print(nativeLight);
        nativeLight.forEach(Statement::detachAndDelete);
        statements.add(first + 1, parser.parseStatement(root,
                "if (!flw_oitActive || flw_overflow != 0u || !flw_ownLightSplit(texel, uvec2(screenSize))) {" + nativeMerge + "} else {"
                        + "solidColor = flw_resolveLight(solidColor, texel, _FLW_REVERSED_DEPTH ? 1.0 : 0.0, waterDepth,"
                        + " uvec2(screenSize)); }"));
    }

    static void initialize(FunctionDefinition scope, Root root, ASTParser parser, String name, String value) {
        if (!(member(scope, root, name).getInitializer() instanceof ExpressionInitializer initializer))
            throw unsupported("rear input " + name);
        initializer.setExpression(parser.parseExpression(root, value));
    }
}
