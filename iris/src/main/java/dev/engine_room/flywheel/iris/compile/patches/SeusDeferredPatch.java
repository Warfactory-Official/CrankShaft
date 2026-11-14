package dev.engine_room.flywheel.iris.compile.patches;

import dev.engine_room.flywheel.iris.compile.GuestShaders;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.loop.ForLoopStatement;
import io.github.douira.glsl_transformer.ast.node.statement.selection.SelectionStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ReturnStatement;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.engine_room.flywheel.iris.compile.patches.DeferredSources.*;

final class SeusDeferredPatch {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> CAMERA_FOG = Set.of("LandAtmosphericScattering", "UnderwaterFog", "UnderLavaFog");
    // Captured records: material and attribute targets of the packed transparent G-buffer.
    private static final String MATERIAL = "vec4(unpackUnorm2x16(flw_nodes[flw_farNode].z), unpackUnorm2x16(flw_nodes[flw_farNode].w))";
    private static final String ATTRIBUTES = "vec4(unpackUnorm2x16(flw_nodes[flw_farNode + 1u].x), unpackUnorm2x16(flw_nodes[flw_farNode + 1u].y))";
    // Lists cover the packed half-resolution quadrant including its edge texel.
    static final String QUADRANT_PASS = "#version 430 compatibility\nlayout(local_size_x = 8, local_size_y = 8) in;\n"
            + "const vec2 workGroupsRender = vec2(0.55, 0.55);\n";

    private SeusDeferredPatch() {
    }

    static String capture(String source, boolean motion, boolean opaqueReady, int snapshotBinding) {
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((tree, root) -> {
            if (!root.identifierIndex.has("OutputGBufferDataTransparent"))
                throw new UnsupportedOperationException("SEUS transparent encoder is missing");
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename("main", "_flw_seusCaptureMain");
            if (!root.identifierIndex.has("screenSize"))
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform vec2 screenSize;");
            if (!root.identifierIndex.has("depthtex1"))
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform sampler2D depthtex1;");
            inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl"),
                    ASTInjectionPoint.BEFORE_DECLARATIONS);
            inject(tree, parser, DeferredOitProfile.snapshot(snapshotBinding)
                            + DeferredOitProfile.resource("seus_capture.glsl")
                                                .replace("_FLW_SEUS_OPAQUE_READY", Boolean.toString(opaqueReady)),
                    ASTInjectionPoint.END);
            DeferredCaptureAllocator.coalesce(tree, parser);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { _flw_seusCaptureMain(); if (gl_HelperInvocation) return;"
                            + " flw_captureSeus(gl_FragData[0], gl_FragData[1], "
                            + (motion ? "gl_FragData[2].xyz" : "vec3(0.0)") + "); }");
        });
        return parser.transform(source);
    }

    /**
     * Late storage writes retain the nearest particle even behind an earlier translucent depth writer. The import
     * compute links that surface's reserved node once per pixel, without allocating for particle fragments.
     */
    static String particles(String source) {
        return transform(source, (tree, root, parser) -> {
            tree.getVersionStatement().version = Version.fromNumber(450);
            root.rename("main", "_flw_seusParticleMain");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "#extension GL_ARB_fragment_shader_interlock : require\n");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "layout(pixel_interlock_unordered) in;");
            if (!root.identifierIndex.has("screenSize"))
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform vec2 screenSize;");
            inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl"),
                    ASTInjectionPoint.BEFORE_DECLARATIONS);
            inject(tree, parser, DeferredOitProfile.resource("seus_particle_storage.glsl"),
                    ASTInjectionPoint.BEFORE_FUNCTIONS);
            inject(tree, parser, DeferredOitProfile.resource("seus_particle_capture.glsl"), ASTInjectionPoint.END);
            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { _flw_seusParticleMain();"
                            + " beginInvocationInterlockARB();"
                            + " if (flw_oitActive && !gl_HelperInvocation"
                            + " && UnpackTwo8BitFrom16Bit(gl_FragData[0].y).y != 0.0)"
                            + " flw_captureParticle(gl_FragData[0], gl_FragData[1]);"
                            + " endInvocationInterlockARB(); }");
        });
    }

    static String clear(String source, boolean particles) {
        if (!particles) return source;
        return transform(source, (tree, _, parser) -> {
            inject(tree, parser, DeferredOitProfile.resource("seus_particle_storage.glsl"),
                    ASTInjectionPoint.BEFORE_FUNCTIONS);
            tree.getOneMainDefinitionBody().getStatements().add(
                    parser.parseStatement(tree.getRoot(), "if (all(lessThan(pixel, uvec2(screenSize) / 2u + 1u)))"
                            + " flw_nodes[flw_particleOffset(pixel, uint(screenSize.x))].x = 0xffffffffu;"));
            tree.getOneMainDefinitionBody().getStatements().add(
                    parser.parseStatement(tree.getRoot(), "if (offset == 0u) {"
                            + " flw_count = flw_particleSlots(uvec2(screenSize));"
                            + " flw_pad = flw_particleSlots(uvec2(screenSize)); }"));
        });
    }

    /**
     * Keeps only the nearest particle record, then sorts the half-resolution capture lists farthest first, staged as
     * a compute before the first composite.
     */
    static String sort(boolean particles) {
        String sort = DeferredOitProfile.resource("layer_sort.glsl")
                                        .replace(
                                                "flw_nodes[flw_node * 2u + 1u].w = floatBitsToUint(transparentDensity);",
                                                "")
                                        .replace("_FLW_VISIBILITY_BODY",
                                                "float flw_depth = uintBitsToFloat(flw_nodes[flw_node].y);"
                                                        + " if (_FLW_REVERSED_DEPTH ? flw_depth > flw_opaqueDepth"
                                                        + " : flw_depth < flw_opaqueDepth) ++flw_visible;")
                                        .replace("_FLW_SORT_PIXEL", "pixel")
                                        .replace("_FLW_SORT_COUNTS_LAYERS", "true")
                                        .replace("_FLW_SORT_LAYER_BIAS", "0u")
                                        .replace("flw_length + 0u", "flw_visible")
                                        .replace("flw_layerBefore(flw_left, flw_right)",
                                                "flw_layerBefore(flw_right, flw_left)")
                                        .replace(" * 2u", "");
        // HRR 3 uses the final word for motion, so every bit participates in an equal-depth tie. Its low half is the
        // motion's half-float depth component, negated in raw reversed depth: flipping its sign keeps the tie order.
        String storage = DeferredOitProfile.resource("layer_storage.glsl")
                                           .replace("return (a.w & FLW_LAYER_UNLIT) <= (b.w & FLW_LAYER_UNLIT);",
                                                   "return _FLW_REVERSED_DEPTH ? (a.w ^ 0x8000u) <= (b.w ^ 0x8000u)"
                                                           + " : a.w <= b.w;");
        String pass = QUADRANT_PASS + "uniform vec2 screenSize;\nuniform sampler2D depthtex1;\n"
                + storage + DeferredOitProfile.resource("seus_particles.glsl")
                + (particles ? DeferredOitProfile.resource("seus_particle_storage.glsl") : "")
                + (particles ? DeferredOitProfile.resource("seus_particle_import.glsl") : "")
                + "void main() {\nuvec2 pixel = gl_GlobalInvocationID.xy;\n"
                + "if (any(greaterThanEqual(pixel, uvec2(screenSize) / 2u + 1u))) return;\nuint flw_visible = 0u;\n"
                + "float flw_opaqueDepth = texelFetch(depthtex1, ivec2(pixel), 0).r;\n"
                + (particles ? "flw_importParticle(ivec2(pixel), flw_opaqueDepth);\n" : "")
                + "if (flw_oitActive)\n"
                + "flw_keepNearestParticle(pixel.y * uint(screenSize.x) + pixel.x, flw_opaqueDepth);\n"
                + sort + "\n}\n";
        return pass;
    }

    /**
     * {@code reversed}: the pack's sources are spelled in raw reversed depth.
     */
    static String sort(boolean particles, String donor, boolean reversed) {
        String source = sort(particles);
        boolean[] lod = {false};
        transform(donor, (_, root, _) -> lod[0] = root.identifierIndex.has("GetViewPositionLod"));
        if (!lod[0]) return source;
        String imported = transform(source, (tree, root, parser) -> PackFunctions.copy(tree, root, parser, donor,
                List.of("GetViewPosition", "GetViewPositionLod", "getLodDepthWater", "getLodDepthSolid"),
                Map.of(), null, true, (_, _) -> {
                }));
        return DeferredLodDepth.seus(imported, reversed);
    }

    /**
     * The pass whose packed-HDR branch reads the background at its own texel and at a refraction target.
     */
    static boolean boundary(String source) {
        boolean[] found = {false};
        var parser = GuestShaders.versionedTransformer();
        parser.setTransformation((_, root) -> {
            var main = function(root, "main");
            var hdr = hdrBranch(main, root, "");
            found[0] = hdr != null && samplerReads(hdr, root, "colortex1").size() == 2;
        });
        parser.transform(source);
        return found[0];
    }

    /**
     * Inline deferred resolve for the boundary pass. {@code stages} lists every chained pass in order (its own index
     * included) with its fragment source and draw buffers; the last composes reflections and camera-side fog.
     * {@code reversed}: the pack's sources are spelled in raw reversed depth.
     */
    static String resolve(String source, int boundary, List<Stage> stages, boolean reversed) {
        return transform(DeferredLodDepth.seus(source, reversed), (tree, root, parser) -> {
            var main = function(root, "main");
            var hdr = hdrBranch(main, root, "");
            if (hdr == null) throw unsupported("SEUS boundary branch");
            if (!root.identifierIndex.has("screenSize"))
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform vec2 screenSize;");
            inject(tree, parser, DeferredOitProfile.resource("layer_storage.glsl"),
                    ASTInjectionPoint.BEFORE_DECLARATIONS);

            // Definitions precede the rear-surface function: donor passes, then record-reading decodes.
            var bodies = new ArrayList<Body>();
            for (var stage : stages)
                bodies.add(stage.pass() == boundary ? new Body((Statement) hdr.cloneInto(root), "", true)
                        : donor(tree, root, parser, stage, reversed));
            for (var body : bodies) decodes(tree, root, parser, body);

            tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "vec4 _flw_farLayer(uint flw_farNode, ivec2 texel, vec2 texcoord, vec4 flw_farBackground, float flw_farBack) {}");
            var far = (FunctionDefinition) tree.getChildren().getLast();
            var statements = far.getBody().getStatements();
            boolean savesTexcoord = root.identifierIndex.has("Texcoord");
            if (savesTexcoord) statements.add(parser.parseStatement(root, "vec2 flw_savedTexcoord = Texcoord;"));
            statements.addAll(registers(root, parser, "flw_farBackground"));
            for (int i = 0; i < bodies.size(); i++) {
                statements.add(bodies.get(i).statement());
                // The first stage traces the reflection; rear surfaces take theirs from SeusRearReflection.
                if (i == 0) untrace(root, parser, bodies.get(i).statement(), stages.get(i).drawBuffers(),
                        RearReflectionCompute.filtered("flw_farNode", SeusRearReflection.SLOT));
                rear(root, parser, bodies.get(i), stages.get(i).drawBuffers(), i == bodies.size() - 1);
            }
            if (savesTexcoord) statements.add(parser.parseStatement(root, "Texcoord = flw_savedTexcoord;"));
            statements.add(parser.parseStatement(root, "return flw_reg1;"));
            int index = stages.stream().map(Stage::pass).toList().indexOf(boundary);
            farRefraction(tree, root, parser, hdr, stages.get(index).drawBuffers(), savesTexcoord, index == 0);
            walker(tree, parser, "vec4", "GetViewPosition(coord, depth).xyz", "flw_heads",
                    DeferredLodDepth.opaque(root, "pixel"));
            pathWalker(tree, parser, "vec4", DeferredLodDepth.opaque(root, "pixel"),
                    "texture2DLod(colortex1, (vec2(pixel) + 0.5) / screenSize + HalfScreen, 0)");

            front(tree, root, parser, hdr);
        });
    }

    /**
     * Copies of the body's G-buffer decodes, prefixed {@code _flw_rear}, reading the record of {@code flw_farNode}.
     */
    private static void decodes(TranslationUnit tree, Root root, ASTParser parser, Body body) {
        for (String decode : List.of("GetGBufferData", "GetGBufferDataTransparent")) {
            String name = body.namespace() + decode;
            String rear = "_flw_rear" + name;
            if (calls(body.statement(), root, name).isEmpty() || root.nodeIndex.getStream(FunctionDefinition.class)
                                                                               .anyMatch(
                                                                                       function -> function.getFunctionPrototype()
                                                                                                           .getName()
                                                                                                           .getName()
                                                                                                           .equals(rear)))
                continue;
            var copy = graft(tree, root, parser, function(root, name),
                    body.namespace() + (decode.equals("GetGBufferData") ? "GBufferData" : "GBufferDataTransparent"),
                    rear, "uint flw_farNode, float flw_farBack");
            redirect(copy, root, parser, Map.of("colortex1", MATERIAL, "colortex2", ATTRIBUTES,
                    "depthtex0", "vec4(uintBitsToFloat(flw_nodes[flw_farNode].y))", "depthtex1", "vec4(flw_farBack)"));
        }
    }

    static void rearDecodes(TranslationUnit tree, Root root, ASTParser parser, CompoundStatement hdr) {
        decodes(tree, root, parser, new Body(hdr, "", true));
    }

    /**
     * Register and texture-coordinate locals of a rear-surface function with parameter {@code texcoord}.
     */
    private static List<Statement> registers(Root root, ASTParser parser, String background) {
        return List.of(parser.parseStatement(root, "vec4 flw_reg1 = " + background + ';'),
                parser.parseStatement(root, "vec4 flw_reg7 = vec4(0.0);"),
                parser.parseStatement(root, "vec4 flw_screenTexcoord = vec4(texcoord + HalfScreen, 0.0, 0.0);"),
                parser.parseStatement(root, "vec4 flw_gbufferTexcoord = vec4(texcoord, 0.0, 0.0);"));
    }

    /**
     * {@code vec4 _flw_rearTrace(uint flw_farNode, ivec2 texel, vec2 texcoord, float flw_farBack)}: the trace stage's
     * packed-HDR branch for one rear surface, up to the reflection it writes to colortex7.
     */
    static void trace(TranslationUnit tree, Root root, ASTParser parser, CompoundStatement hdr, int[] buffers) {
        rearDecodes(tree, root, parser, hdr);
        tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                "vec4 _flw_rearTrace(uint flw_farNode, ivec2 texel, vec2 texcoord, float flw_farBack) {}");
        var trace = (FunctionDefinition) tree.getChildren().getLast();
        trace.getBody().getStatements().addAll(registers(root, parser, "vec4(0.0)"));
        var branch = (CompoundStatement) hdr.cloneInto(root);
        trace.getBody().getStatements().add(branch);
        var output = reflectionOutput(root, branch, buffers);
        if (output.getParent() != branch) throw unsupported("SEUS reflection output is conditional");
        var traced = traced(root, branch, output);
        // The output's inputs are final after the last statement naming one of them.
        int last = -1;
        var block = branch.getStatements();
        for (int i = 0; i < block.indexOf(output); i++) {
            var statement = block.get(i);
            if (!names(root, statement, traced).isEmpty() || root.nodeIndex.getStream(DeclarationMember.class)
                                                                           .anyMatch(member -> member.hasAncestor(
                                                                                   statement) && traced.contains(
                                                                                   member.getName().getName())))
                last = i;
        }
        var exit = parser.parseStatement(root, "return "
                + ASTPrinter.printSimple(((AssignmentExpression) output.getExpression()).getRight()) + ';');
        block.add(last + 1, exit);
        truncate(trace, exit, false);
        rear(root, parser, new Body(branch, "", true), buffers, false);
    }

    /**
     * The stage's single {@code gl_FragData} write landing in colortex7.
     */
    private static ExpressionStatement reflectionOutput(Root root, Statement scope, int[] buffers) {
        var outputs = root.nodeIndex.getStream(ExpressionStatement.class)
                                    .filter(statement -> statement.hasAncestor(scope) && "gl_FragData".equals(
                                            written(statement))
                                            && target(statement, buffers) == 7).toList();
        if (outputs.size() != 1) throw unsupported("SEUS reflection output x" + outputs.size());
        return outputs.getFirst();
    }

    /**
     * Locals of {@code scope} that {@code output} reads: the trace results.
     */
    private static Set<String> traced(Root root, Statement scope, ExpressionStatement output) {
        var names = names(root, ((AssignmentExpression) output.getExpression()).getRight(), null);
        names.removeIf(name -> root.nodeIndex.getStream(DeclarationMember.class)
                                             .noneMatch(
                                                     member -> member.hasAncestor(scope) && member.getName().getName()
                                                                                                  .equals(name)));
        if (names.isEmpty()) throw unsupported("SEUS reflection trace");
        return names;
    }

    /**
     * Variables {@code node} reads or writes, of {@code among} when not null.
     */
    private static Set<String> names(Root root, ASTNode node, Set<String> among) {
        var names = new LinkedHashSet<String>();
        for (var reference : root.nodeIndex.getStream(ReferenceExpression.class).toList()) {
            String name = reference.getIdentifier().getName();
            if ((reference == node || reference.hasAncestor(node)) && (among == null || among.contains(name)))
                names.add(name);
        }
        return names;
    }

    /**
     * Replaces the trace stage's reflection output with {@code value} and drops the assignments of the trace.
     */
    private static void untrace(Root root, ASTParser parser, Statement scope, int[] buffers, String value) {
        var output = reflectionOutput(root, scope, buffers);
        var traced = traced(root, scope, output);
        ((AssignmentExpression) output.getExpression()).getRight()
                                                       .replaceByAndDelete(parser.parseExpression(root, value));
        for (var statement : root.nodeIndex.getStream(ExpressionStatement.class).toList())
            if (statement.hasAncestor(scope) && traced.contains(written(statement)))
                statement.replaceByAndDelete(parser.parseStatement(root, ";"));
        if (!names(root, scope, traced).isEmpty()) throw unsupported("SEUS reflection trace has other uses");
    }

    private static int target(ExpressionStatement statement, int[] buffers) {
        var assignment = (AssignmentExpression) statement.getExpression();
        String left = ASTPrinter.printSimple(assignment.getLeft()).replaceAll("\\s+", "");
        return buffers[Integer.parseInt(left.substring("gl_FragData[".length(), left.indexOf(']')))];
    }

    /**
     * Imports a chained pass under its own namespace; returns a detached copy of its packed-HDR body.
     */
    private static Body donor(TranslationUnit tree, Root root, ASTParser parser, Stage stage, boolean reversed) {
        Body[] body = {null};
        String namespace = "_flw_c" + stage.pass() + '_';
        PackFunctions.copy(tree, root, parser, DeferredLodDepth.seus(stage.source(), reversed), List.of(), Map.of(),
                namespace, false,
                (library, importer) -> {
                    var main = function(library.getRoot(), "main");
                    var split = hdrBranch(main, library.getRoot(), namespace);
                    var branch = split == null ? main.getBody() : split;
                    importer.dependencies(branch);
                    body[0] = new Body((Statement) branch.cloneInto(root), namespace, split != null);
                });
        return body[0];
    }

    /**
     * Turns one chained pass body inside the rear-surface function into register and record reads/writes.
     */
    private static void rear(Root root, ASTParser parser, Body body, int[] buffers, boolean compose) {
        var scope = body.statement();
        for (var identifier : root.identifierIndex.getStream("texcoord").toList())
            if (identifier.hasAncestor(scope))
                identifier.setName(body.screen() ? "flw_screenTexcoord" : "flw_gbufferTexcoord");
        for (String decode : List.of("GetGBufferData", "GetGBufferDataTransparent")) {
            String name = body.namespace() + decode;
            for (var call : calls(scope, root, name)) {
                String arguments = call.getParameters().stream().map(ASTPrinter::printSimple)
                                       .reduce((a, b) -> a + ", " + b).orElseThrow();
                call.replaceByAndDelete(parser.parseExpression(root,
                        "_flw_rear" + name + '(' + arguments + ", flw_farNode, flw_farBack)"));
            }
        }
        redirect(scope, root, parser, Map.of("colortex1", "flw_reg1", "colortex7", "flw_reg7",
                "depthtex0", "vec4(uintBitsToFloat(flw_nodes[flw_farNode].y))", "depthtex1", "vec4(flw_farBack)"));
        for (var statement : root.nodeIndex.getStream(ExpressionStatement.class).toList()) {
            if (!statement.hasAncestor(scope) || !"gl_FragData".equals(written(statement))) continue;
            int target = target(statement, buffers);
            statement.replaceByAndDelete(parser.parseStatement(root, target == 1 || target == 7 ? "flw_reg" + target
                    + " = " + ASTPrinter.printSimple(
                    ((AssignmentExpression) statement.getExpression()).getRight()) + ';' : ";"));
        }
        if (!compose) return;
        // Camera-to-surface fog and shafts belong to the front surface alone.
        var removed = new LinkedHashSet<Statement>();
        for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
            if (call.getFunctionName() == null || !call.hasAncestor(scope)) continue;
            String name = call.getFunctionName().getName();
            if (!name.startsWith(body.namespace())) continue;
            name = name.substring(body.namespace().length());
            if (name.equals("WorldPosToShadowProjPos")) {
                var loop = call.getAncestor(ForLoopStatement.class);
                if (loop != null && loop.hasAncestor(scope)) removed.add(loop);
            }
            var statement = call.getAncestor(ExpressionStatement.class);
            if (CAMERA_FOG.contains(name) && statement != null && statement.getExpression() == call)
                removed.add(statement);
        }
        for (var statement : removed) statement.replaceByAndDelete(parser.parseStatement(root, ";"));
    }

    /**
     * The boundary branch for one rear surface, up to its refracted background read; its plane.
     */
    private static void farRefraction(TranslationUnit tree, Root root, ASTParser parser, CompoundStatement hdr,
                                      int[] buffers, boolean savesTexcoord, boolean traces) {
        tree.parseAndInjectNode(parser, ASTInjectionPoint.END, "vec2 _flw_farRefraction(uint flw_farNode, ivec2 texel, "
                + "vec2 texcoord, float flw_farBack, out vec4 flw_farPlane) {}");
        var path = (FunctionDefinition) tree.getChildren().getLast();
        var statements = path.getBody().getStatements();
        if (savesTexcoord) statements.add(parser.parseStatement(root, "vec2 flw_savedTexcoord = Texcoord;"));
        statements.addAll(registers(root, parser, "vec4(0.0)"));
        var branch = (CompoundStatement) hdr.cloneInto(root);
        statements.add(branch);
        if (traces) untrace(root, parser, branch, buffers, "vec4(0.0)");
        var transparent = calls(branch, root, "GetGBufferDataTransparent");
        if (transparent.size() != 1) throw unsupported("SEUS rear transparent decode");
        var member = transparent.getFirst().getAncestor(DeclarationMember.class);
        String surface = member.getName().getName();
        String coord = ASTPrinter.printSimple(transparent.getFirst().getParameters().getFirst()).strip();
        var declaration = member.getAncestor(DeclarationStatement.class);
        branch.getStatements().addAll(branch.getStatements().indexOf(declaration) + 1, List.of(
                parser.parseStatement(root,
                        "vec3 flw_surface = GetViewPosition(" + coord + ", " + surface + ".depth).xyz;"),
                parser.parseStatement(root, "vec3 flw_planeNormal = " + surface + ".geoNormal * (dot(" + surface
                        + ".geoNormal, flw_surface) < 0.0 ? -1.0 : 1.0);"),
                parser.parseStatement(root,
                        "flw_farPlane = vec4(flw_planeNormal, -dot(flw_planeNormal, flw_surface));")));
        var refracted = samplerReads(branch, root, "colortex1").stream().filter(color -> nested(color, branch))
                                                               .toList();
        if (refracted.size() != 1) throw unsupported("SEUS rear refracted read");
        String target = ASTPrinter.printSimple(refracted.getFirst().getParameters().get(1)).strip();
        var read = refracted.getFirst().getAncestor(Statement.class);
        var block = (CompoundStatement) read.getParent();
        var exit = parser.parseStatement(root, "{" + (savesTexcoord ? "Texcoord = flw_savedTexcoord; " : "")
                + "return (" + target + ") - HalfScreen; }");
        block.getStatements().add(block.getStatements().indexOf(read), exit);
        truncate(path, exit, false);
        rear(root, parser, new Body(branch, "", true), buffers, false);
        if (savesTexcoord) statements.add(parser.parseStatement(root, "Texcoord = flw_savedTexcoord;"));
        statements.add(parser.parseStatement(root, "return texcoord;"));
    }

    /**
     * Native front surface: background reads compose the captured surfaces behind it, depth reads see them.
     */
    private static void front(TranslationUnit tree, Root root, ASTParser parser, CompoundStatement hdr) {
        var transparent = calls(hdr, root, "GetGBufferDataTransparent");
        var opaque = calls(hdr, root, "GetGBufferData");
        if (transparent.size() != 1 || opaque.size() != 1) throw unsupported("SEUS boundary decode");
        var member = transparent.getFirst().getAncestor(DeclarationMember.class);
        if (member == null) throw unsupported("SEUS transparent declaration");
        String surface = member.getName().getName();
        String coord = ASTPrinter.printSimple(transparent.getFirst().getParameters().getFirst()).strip();
        String front = surface + ".depth";

        var nearOpaque = graft(tree, root, parser, function(root, "GetGBufferData"), "GBufferData",
                "_flw_nearGBufferData", "");
        declareBefore(tree, parser, nearOpaque, function(root, "main"));
        var depthReads = samplerReads(nearOpaque, root, "depthtex1");
        boolean normalized = root.identifierIndex.has("_flw_lodDepth");
        if (depthReads.size() != (normalized ? 2 : 1)
                || normalized && calls(nearOpaque, root, "_flw_lodDepth").size() != 1)
            throw unsupported("SEUS opaque depth read");
        var returns = root.nodeIndex.getStream(ReturnStatement.class)
                                    .filter(exit -> exit.hasAncestor(nearOpaque)).toList();
        if (returns.size() != 1) throw unsupported("SEUS opaque decode return");
        var exit = returns.getFirst();
        String result = ASTPrinter.printSimple(exit.getExpression()).strip();
        if (!IDENTIFIER.matcher(result).matches()) throw unsupported("SEUS opaque decode result");
        String at = nearOpaque.getFunctionPrototype().getParameters().getFirst().getName().getName();
        // Native material selection needs its original depths; rear-layer queries consume the completed decode.
        var decodeBody = (CompoundStatement) exit.getParent();
        decodeBody.getStatements().add(decodeBody.getStatements().indexOf(exit), parser.parseStatement(root,
                result + ".depth=flw_farDepth(ivec2(" + at + " * screenSize), "
                        + DeferredLodDepth.depth(root, at, "texture2DLod(depthtex0," + at + ",0).x", false)
                        + ", vec4(0.0), false, " + result + ".depth);"));
        opaque.getFirst().replaceByAndDelete(parser.parseExpression(root, "_flw_nearGBufferData("
                + ASTPrinter.printSimple(opaque.getFirst().getParameters().getFirst()) + ')'));

        var statement = member.getAncestor(DeclarationStatement.class);
        var statements = hdr.getStatements();
        statements.addAll(statements.indexOf(statement) + 1, List.of(
                parser.parseStatement(root, "vec3 flw_surface = GetViewPosition(" + coord + ", " + front + ").xyz;"),
                parser.parseStatement(root, "vec3 flw_planeNormal = " + surface + ".geoNormal * (dot(" + surface
                        + ".geoNormal, flw_surface) < 0.0 ? -1.0 : 1.0);"),
                parser.parseStatement(root,
                        "vec4 flw_farPlane = vec4(flw_planeNormal, -dot(flw_planeNormal, flw_surface));"),
                parser.parseStatement(root, "ivec2 flw_farPixel = ivec2(" + coord + " * screenSize);")));

        for (var depth : samplerReads(hdr, root, "depthtex1")) {
            String target = ASTPrinter.printSimple(depth.getParameters().get(1)).strip();
            boolean own = target.replaceAll("\\s+", "").equals(coord.replaceAll("\\s+", ""));
            depth.replaceByAndDelete(parser.parseExpression(root, "vec4(flw_farDepth(" + (own ? "flw_farPixel"
                    : "ivec2((" + target + ") * screenSize)") + ", " + front + ", flw_farPlane, " + !own + ", "
                    + DeferredLodDepth.depth(root, target, ASTPrinter.printSimple(depth) + ".x", true) + "))"));
        }

        // The index is unordered: the own read is the unconditional one, the refracted read sits under a selection.
        var colors = samplerReads(hdr, root, "colortex1");
        var conditional = colors.stream().filter(color -> nested(color, hdr)).toList();
        if (colors.size() != 2 || conditional.size() != 1) throw unsupported("SEUS background reads");
        var refracted = conditional.getFirst();
        var own = colors.get(colors.get(0) == refracted ? 1 : 0);
        var ownStatement = topLevel(own, hdr);
        // Selections between the boundary branch and the refracted read decide whether the own background is used.
        var path = new ArrayList<SelectionStatement>();
        for (ASTNode node = refracted.getParent(); node != hdr; node = node.getParent())
            if (node instanceof SelectionStatement selection) path.addFirst(selection);
        StringBuilder attempted = new StringBuilder("true");
        SelectionStatement late = null;
        for (var selection : path) {
            String condition = ASTPrinter.printSimple(selection.getCondition());
            if (!known(condition, hdr, ownStatement, root)) {
                late = selection;
                break;
            }
            attempted.append(" && ").append(refracted.hasAncestor(selection.getIfTrue()) ? "(" : "!(")
                     .append(condition).append(')');
        }
        String ownRead = ASTPrinter.printSimple(own);
        String ownComposite = "flw_farPath(flw_farPixel, " + front + ", flw_farPlane, false, " + ownRead + ")";
        String fallback = null;
        if (late != null) {
            // The own background stays native while the refraction is attempted; a failed attempt composes it here.
            if (late.getIfFalse() != null || !(late.getIfTrue() instanceof CompoundStatement taken)
                    || !refracted.hasAncestor(taken)) throw unsupported("SEUS refraction fallback");
            var assignment = topLevel(refracted, taken);
            var block = taken.getStatements();
            var copy = (Statement) assignment.cloneInto(root);
            var copies = samplerReads(copy, root, "colortex1");
            if (copies.size() != 1) throw unsupported("SEUS refraction read statement");
            copies.getFirst().replaceByAndDelete(parser.parseExpression(root, ownComposite));
            fallback = ASTPrinter.printSimple(copy);
            copy.detachAndDelete();
            int next = block.indexOf(assignment) + 1;
            if (next < block.size() && written(block.get(next)) != null && written(block.get(next)).equals(
                    written(assignment)))
                fallback += ASTPrinter.printSimple(block.get(next));
        }
        String target = ASTPrinter.printSimple(refracted.getParameters().get(1)).strip();
        refracted.replaceByAndDelete(parser.parseExpression(root, "flw_farPath(ivec2(((" + target
                + ") - HalfScreen) * screenSize), " + front + ", flw_farPlane, true, " + ASTPrinter.printSimple(
                refracted) + ")"));
        if (late != null)
            late.replaceByAndDelete(parser.parseStatement(root, "if (" + ASTPrinter.printSimple(late.getCondition())
                    + ") " + ASTPrinter.printSimple(late.getIfTrue()) + " else {" + fallback + "}"));
        own.replaceByAndDelete(
                parser.parseExpression(root, "((" + attempted + ") ? " + ownRead + " : " + ownComposite + ")"));
    }

    /**
     * Whether every variable in {@code condition} is in scope, with its final value, at the own background read.
     */
    private static boolean known(String condition, CompoundStatement hdr, Statement read, Root root) {
        var matcher = IDENTIFIER.matcher(condition);
        int limit = hdr.getStatements().indexOf(read);
        while (matcher.find()) {
            String name = matcher.group();
            for (var member : root.nodeIndex.getStream(DeclarationMember.class).toList()) {
                if (!member.hasAncestor(hdr) || !member.getName().getName().equals(name)) continue;
                var statement = member.getAncestor(DeclarationStatement.class);
                if (statement.getParent() != hdr || hdr.getStatements().indexOf(statement) >= limit) return false;
            }
        }
        return true;
    }

    private static boolean nested(ASTNode node, CompoundStatement block) {
        for (ASTNode current = node.getParent(); current != block; current = current.getParent())
            if (current instanceof SelectionStatement) return true;
        return false;
    }

    private static Statement topLevel(ASTNode node, CompoundStatement block) {
        ASTNode current = node;
        while (current.getParent() != block) current = current.getParent();
        return (Statement) current;
    }

    /**
     * The else branch of main's packed-half-screen split that decodes the transparent G-buffer, or null.
     * {@code namespace}: the donor prefix of the unit's functions.
     */
    static CompoundStatement hdrBranch(FunctionDefinition main, Root root, String namespace) {
        for (var selection : root.nodeIndex.getStream(SelectionStatement.class).toList()) {
            if (selection.getParent() != main.getBody() || selection.getIfFalse() == null
                    || !ASTPrinter.printSimple(selection.getCondition()).contains("HalfScreen")
                    || !(selection.getIfFalse() instanceof CompoundStatement branch)
                    || calls(branch, root, namespace + "GetGBufferDataTransparent").isEmpty()) continue;
            return branch;
        }
        return null;
    }

    /**
     * One chained pass: index, preprocessed fragment source and draw buffers.
     */
    record Stage(int pass, String source, int[] drawBuffers) {
    }

    /**
     * A chained pass body: its donor namespace and whether it samples with full-screen texcoords.
     */
    private record Body(Statement statement, String namespace, boolean screen) {
    }
}
