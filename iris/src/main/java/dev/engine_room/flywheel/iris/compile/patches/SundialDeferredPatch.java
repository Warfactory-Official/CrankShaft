package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanExpression;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.ArrayList;
import java.util.List;

final class SundialDeferredPatch {
    private SundialDeferredPatch() {
    }

    static String capture(String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            for (String output : List.of("gbufferData0", "gbufferData1", "gbufferData2")) {
                if (!root.identifierIndex.has(output)) throw unsupported("missing output " + output);
            }
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename("main", "_flw_layerMain");
            inject(tree, parser, DeferredOitProfile.resource("layer_capture.glsl"), ASTInjectionPoint.END);
            DeferredCaptureAllocator.coalesce(tree, parser);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { _flw_layerMain(); if (gl_HelperInvocation) return; flw_captureLayer(gbufferData0, gbufferData1, gbufferData2); }");
        });
        return parser.transform(source);
    }

    static String lightSort(String pixel) {
        return DeferredOitProfile.resource("layer_sort.glsl")
                                 .replace("_FLW_SORT_PIXEL", pixel)
                                 .replace("flw_heads", "flw_lightHeads")
                                 .replace("flw_layerBefore", "flw_lightBefore")
                                 .replace(" * 2u", "")
                                 .replace("_FLW_VISIBILITY_BODY", "")
                                 .replace("flw_nodes[flw_node + 1u].w = floatBitsToUint(transparentDensity);", "")
                                 .replace("_FLW_SORT_COUNTS_LAYERS", "false")
                                 .replace("_FLW_SORT_LAYER_BIAS", "0u");
    }

    static String materialSort() {
        String sort = DeferredOitProfile.resource("layer_sort.glsl");
        String store = "flw_nodes[flw_node * 2u + 1u].w = floatBitsToUint(transparentDensity);";
        if (sort.indexOf(store) < 0 || sort.indexOf(store) != sort.lastIndexOf(store))
            throw unsupported("cloud transmission store");
        return sort.replace(store,
                "flw_nodes[flw_node * 2u + 1u].w = (floatBitsToUint(transparentDensity) & 0x7fffffffu)"
                        + " | (flw_nodes[flw_node * 2u + 1u].w & FLW_LAYER_UNLIT);");
    }

    static String captureLight(String source, boolean early) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename("main", "_flw_lightMain");
            if (!root.identifierIndex.has("screenSize")) tree.parseAndInjectNode(parser,
                    ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform vec2 screenSize;");
            if (!root.identifierIndex.has("depthtex1")) tree.parseAndInjectNode(parser,
                    ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform sampler2D depthtex1;");
            inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl")
                    + DeferredOitProfile.resource("layer_light.glsl"), ASTInjectionPoint.BEFORE_DECLARATIONS);
            DeferredCaptureAllocator.coalesce(tree, parser);
            String value = root.identifierIndex.has("guestUnlit")
                    ? "vec4(guestUnlit.rgb * guestUnlit.a, guestUnlit.a)" : "vec4(guestLight.rgb, 0.0)";
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { _flw_lightMain(); if (gl_HelperInvocation) return; ivec2 pixel = ivec2(gl_FragCoord.xy);"
                            + (early ? "" : " if (gl_FragCoord.z < texelFetch(depthtex1, pixel, 0).x)")
                            + " flw_captureLight(pixel, gl_FragCoord.z, " + value + ", uint(screenSize.x)); }");
        });
        return parser.transform(source);
    }

    static String composite(int index, String source) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            tree.getVersionStatement().version = Version.fromNumber(430);
            var body = tree.getOneMainDefinitionBody();
            List<Statement> statements = body.getStatements();
            if (index == 0) {
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
                         if (!(member.getInitializer() instanceof ExpressionInitializer initializer)) {
                             throw unsupported("cloud depth initializer");
                         }
                         initializer.setExpression(
                                 parser.parseExpression(root, "uintBitsToFloat(flw_nodes[flw_node].y)"));
                         replaced[0]++;
                     });
                if (replaced[0] != 1) throw unsupported("cloud layer depth input");
                String perLayer = print(block.getStatements());
                block.detachAndDelete();
                inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl")
                                + DeferredOitProfile.resource("layer_light.glsl"),
                        ASTInjectionPoint.BEFORE_DECLARATIONS);
                statements.add(last, parser.parseStatement(root,
                        materialSort().replace("_FLW_VISIBILITY_BODY", perLayer)
                                      .replace(" * 2u", "")
                                      .replace("_FLW_SORT_COUNTS_LAYERS", "true")
                                      .replace("_FLW_SORT_LAYER_BIAS", "0u")
                                      .replace("_FLW_SORT_PIXEL", "uvec2(gl_FragCoord.xy)")));
                statements.add(last + 1, parser.parseStatement(root, lightSort("uvec2(gl_FragCoord.xy)")));
            } else {
                tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform int flw_oitFarLayer;");
                if (index == 1 || index == 3 || index == 6) {
                    tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                            "uniform bool flw_oitReplaying;",
                            "layout(r32ui,binding=7) uniform readonly uimage2D flw_layerNode;");
                    for (var comparison : root.nodeIndex.getStream(LessThanExpression.class).toList()) {
                        if (!ASTPrinter.printSimple(comparison.getLeft()).strip().equals("waterDepth")) continue;
                        String right = ASTPrinter.printSimple(comparison.getRight()).strip();
                        if (!right.equals("solidDepth") && !right.startsWith("texelFetch(depthtex1,")) continue;
                        comparison.replaceByAndDelete(parser.parseExpression(root,
                                "(" + ASTPrinter.printSimple(
                                        comparison) + ") || (flw_oitReplaying && imageLoad(flw_layerNode, ivec2(gl_FragCoord.xy)).r != 0u)"));
                    }
                }
                if (index == 1) {
                    inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl")
                            + DeferredOitProfile.resource("layer_light.glsl"), ASTInjectionPoint.BEFORE_DECLARATIONS);
                    int background = declaration(statements, "solidColor");
                    if (background < 0) throw unsupported("material background");
                    statements.add(background + 1, parser.parseStatement(root,
                            "solidColor.rgb = flw_resolveLight(solidColor.rgb, texel, waterDepth, solidDepth, uvec2(screenSize));"));
                    int pixel = declaration(statements, "texel");
                    if (pixel < 0) throw unsupported("material pixel");
                    statements.add(pixel + 1, parser.parseStatement(root, """
                            uint flw_materialNode = flw_oitReplaying ? imageLoad(flw_layerNode, texel).r : 0u;
                            """));
                    statements.add(pixel + 2, parser.parseStatement(root, """
                            bool flw_layerUnlit = flw_materialNode != 0u
                                    && (flw_nodes[flw_materialNode].w & FLW_LAYER_UNLIT) != 0u;
                            """));
                    int unlit = 0;
                    for (var member : root.nodeIndex.getStream(DeclarationMember.class).toList()) {
                        if (!member.getName().getName().equals("volumetricLight")) continue;
                        if (!(member.getInitializer() instanceof ExpressionInitializer initializer)
                                || !ASTPrinter.printSimple(initializer.getExpression())
                                              .contains("gbufferData.emissive"))
                            throw unsupported("material radiance");
                        initializer.setExpression(parser.parseExpression(root,
                                "flw_layerUnlit ? gbufferData.albedo.rgb * gbufferData.albedo.w : ("
                                        + ASTPrinter.printSimple(initializer.getExpression()) + ")"));
                        unlit++;
                    }
                    if (unlit != 1) throw unsupported("material radiance count");
                    for (var statement : root.nodeIndex.getStream(ExpressionStatement.class).toList()) {
                        if (!ASTPrinter.printSimple(statement).replaceAll("\\s+", "")
                                       .equals("volumetricLight+=shadow;")) continue;
                        statement.replaceByAndDelete(parser.parseStatement(root,
                                "if (!flw_layerUnlit) { " + ASTPrinter.printSimple(statement) + " }"));
                    }
                    tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                            "vec3 flw_resolveRefractedLight(vec3 background, vec2 coord, float back, vec4 plane);");
                    inject(tree, parser, DeferredOitProfile.resource("layer_refracted_light.glsl"),
                            ASTInjectionPoint.END);
                    int waterPosition = declaration(statements, "waterWorldDir");
                    if (waterPosition < 0) throw unsupported("view direction");
                    statements.add(waterPosition + 1, parser.parseStatement(root,
                            "vec3 flw_planeNormal = gbufferData.geoNormal * (dot(gbufferData.geoNormal, waterViewPos) < 0.0 ? -1.0 : 1.0);"));
                    for (var assignment : root.nodeIndex.getStream(AssignmentExpression.class).toList()) {
                        if (!ASTPrinter.printSimple(assignment.getLeft()).strip().equals("solidColor.rgb")) continue;
                        String value = ASTPrinter.printSimple(assignment.getRight());
                        if (!value.startsWith("texelFetch(colortex5,")) continue;
                        assignment.setRight(parser.parseExpression(root,
                                "flw_resolveRefractedLight(" + value + ", refractionTarget, targetSolidDepth, vec4(flw_planeNormal, -dot(flw_planeNormal, waterViewPos)))"));
                    }
                    int first = declaration(statements, "absorption");
                    int last = assignment(statements, "texBuffer4");
                    if (first < 0 || last <= first) throw unsupported("front fog range");
                    guard(statements, first + 1, last, parser, root);
                } else if (index == 2) {
                    statements.addFirst(parser.parseStatement(root, """
                            if (flw_oitFarLayer != 0) {
                                ivec2 pixel = ivec2(gl_FragCoord.xy);
                                texBuffer4 = texelFetch(colortex4, pixel, 0);
                                texBuffer5 = texelFetch(colortex5, pixel, 0);
                                return;
                            }
                            """));
                } else if (index == 6) {
                    inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl")
                            + DeferredOitProfile.resource("layer_light.glsl"), ASTInjectionPoint.BEFORE_DECLARATIONS);
                    int first = declaration(statements, "_flw_guestLight");
                    int last = assignment(statements, "texBuffer5");
                    if (first < 0 || last <= first) throw unsupported("guest light range");
                    if (declaration(statements, "_flw_guestUnlit") != first + 2)
                        throw unsupported("unlit opacity merge");
                    List<Statement> nativeLight = new ArrayList<>(statements.subList(first + 1, first + 4));
                    String nativeMerge = print(nativeLight);
                    nativeLight.forEach(Statement::detachAndDelete);
                    statements.add(first + 1, parser.parseStatement(root,
                            "if (!flw_oitActive || flw_overflow != 0u) {" + nativeMerge + "} else {"
                                    + "solidColor = flw_resolveLight(solidColor, texel, 0.0, waterDepth, uvec2(screenSize)); }"));
                    last = assignment(statements, "texBuffer5");
                    guard(statements, first, last, parser, root);
                }
            }
        });
        return parser.transform(source);
    }

    private static void guard(List<Statement> statements, int first, int last, ASTParser parser, Root root) {
        List<Statement> selected = new ArrayList<>(statements.subList(first, last));
        String replacement = "if (flw_oitFarLayer == 0) {" + print(selected) + "}";
        for (Statement statement : selected) statement.detachAndDelete();
        statements.add(first, parser.parseStatement(root, replacement));
    }

    private static void inject(TranslationUnit tree, ASTParser parser, String source, ASTInjectionPoint point) {
        var library = parser.parseTranslationUnit(tree.getRoot(), "#version 430 compatibility\n" + source);
        tree.injectNodes(point, library.getChildren().stream().map(node -> node.cloneInto(tree.getRoot())).toList());
        library.detachAndDelete();
    }

    private static int declaration(List<Statement> statements, String name) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i) instanceof DeclarationStatement statement
                    && statement.getDeclaration() instanceof TypeAndInitDeclaration declaration
                    && declaration.getMembers().stream().anyMatch(member -> member.getName().getName().equals(name)))
                return i;
        }
        return -1;
    }

    private static int assignment(List<Statement> statements, String name) {
        for (int i = 0; i < statements.size(); i++) {
            if (statements.get(i) instanceof ExpressionStatement statement
                    && statement.getExpression() instanceof AssignmentExpression assignment
                    && ASTPrinter.printSimple(assignment.getLeft()).strip().equals(name)) return i;
        }
        return -1;
    }

    private static String print(List<Statement> statements) {
        StringBuilder result = new StringBuilder();
        for (Statement statement : statements) result.append(ASTPrinter.printSimple(statement)).append('\n');
        return result.toString();
    }

    private static UnsupportedOperationException unsupported(String reason) {
        return new UnsupportedOperationException("Sundial deferred adapter: " + reason);
    }
}
