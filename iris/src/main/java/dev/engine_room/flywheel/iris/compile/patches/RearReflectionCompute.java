package dev.engine_room.flywheel.iris.compile.patches;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.Statement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.DeclarationStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ExpressionStatement;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.engine_room.flywheel.iris.compile.patches.DeferredSources.*;

/**
 * Turns a pack's preprocessed fragment program into a per-pixel compute pass over captured surfaces: the program's
 * vertex outputs, fragment-position globals and stage interface are rebuilt for an invocation. Records carry the
 * rear reflection in one slot: raw half4 in {@code xy}, filtered half4 in {@code zw}.
 */
final class RearReflectionCompute {
    private static final Set<String> VERTEX_ONLY = Set.of("gl_Position", "gl_VertexID", "gl_InstanceID", "gl_Vertex",
            "gl_MultiTexCoord0", "vaPosition", "vaUV0");
    private static final Set<String> DERIVATIVES = Set.of("dFdx", "dFdy", "fwidth", "dFdxFine", "dFdyFine",
            "fwidthFine",
            "dFdxCoarse", "dFdyCoarse", "fwidthCoarse");
    private static final Map<String, String> IMPLICIT_LOD = Map.of("texture", "textureLod", "texture2D", "texture2DLod",
            "texture3D", "texture3DLod", "textureCube", "textureCubeLod", "shadow2D", "shadow2DLod");

    private RearReflectionCompute() {
    }

    static String filtered(String node, String slot) {
        return "vec4(unpackHalf2x16(flw_nodes[" + node + " + " + slot + "].z), unpackHalf2x16(flw_nodes[" + node + " + "
                + slot + "].w))";
    }

    /**
     * {@code gl_FragCoord} reads become {@code _flw_fragCoord}, which the pass's main sets per invocation.
     */
    static void fragmentPosition(TranslationUnit tree, Root root, ASTParser parser) {
        root.rename("gl_FragCoord", "_flw_fragCoord");
        tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "vec4 _flw_fragCoord;");
    }

    /**
     * The program's vertex outputs, computed per invocation by {@code _flw_vertexInputs()}. Statements reached by
     * vertex-only inputs (position, vertex index) are dropped with everything they feed; the pass sets those outputs.
     */
    static void vertexInputs(TranslationUnit tree, Root root, ASTParser parser, String vertex) {
        CompoundStatement[] body = {null};
        // Vertex helpers may share names with the fragment's; stage outputs keep theirs.
        PackFunctions.copy(tree, root, parser, vertex, List.of(), Map.of(), "_flw_vs_", false, (library, importer) -> {
            var main = function(library.getRoot(), "main");
            Set<String> tainted = new HashSet<>(VERTEX_ONLY);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (var statement : List.copyOf(main.getBody().getStatements())) {
                    if (!reads(library.getRoot(), statement, tainted)) continue;
                    tainted.addAll(defines(library.getRoot(), statement));
                    statement.detachAndDelete();
                    changed = true;
                }
            }
            for (var statement : library.getRoot().nodeIndex.getStream(ExpressionStatement.class).toList())
                if (statement.hasAncestor(main) && statement.getExpression() instanceof FunctionCallExpression call
                        && call.getFunctionName() != null && call.getFunctionName().getName().equals("imageStore"))
                    statement.detachAndDelete();
            for (String builtin : VERTEX_ONLY)
                if (library.getRoot().identifierIndex.getStream(builtin)
                                                     .anyMatch(identifier -> identifier.hasAncestor(main)))
                    throw unsupported("vertex stage reads " + builtin + " below its top level");
            importer.dependencies(main);
            body[0] = (CompoundStatement) main.getBody().cloneInto(root);
        });
        tree.parseAndInjectNode(parser, ASTInjectionPoint.END, "void _flw_vertexInputs() {}");
        ((FunctionDefinition) tree.getChildren().getLast()).getBody().replaceByAndDelete(body[0]);
    }

    private static boolean reads(Root root, Statement statement, Set<String> names) {
        return names.stream().anyMatch(name -> root.identifierIndex.getStream(name)
                                                                   .anyMatch(identifier -> identifier.hasAncestor(
                                                                           statement)));
    }

    private static Set<String> defines(Root root, Statement statement) {
        var names = new HashSet<String>();
        if (statement instanceof DeclarationStatement declaration) root.nodeIndex.getStream(DeclarationMember.class)
                                                                                 .filter(member -> member.hasAncestor(
                                                                                         declaration)).forEach(
                        member -> names.add(member.getName().getName()));
        root.nodeIndex.getStream(ExpressionStatement.class).filter(expression -> expression.hasAncestor(statement)
                || expression == statement).forEach(expression -> {
            String written = written(expression);
            if (written != null) names.add(written);
        });
        return names;
    }

    /**
     * Moves global initializers that depend on the fragment position into per-invocation assignments, returned in
     * declaration order.
     */
    static String pixelGlobals(TranslationUnit tree) {
        var dependent = new ArrayList<String>();
        var assignments = new ArrayList<String>();
        for (var child : tree.getChildren()) {
            if (!(child instanceof DeclarationExternalDeclaration external)
                    || !(external.getDeclaration() instanceof TypeAndInitDeclaration declaration)) continue;
            for (var member : declaration.getMembers()) {
                if (member.getInitializer() == null) continue;
                String value = ASTPrinter.printSimple(member.getInitializer()).strip();
                boolean pixel = value.matches("(?s).*\\b_flw_fragCoord\\b.*")
                        || dependent.stream().anyMatch(name -> value.matches("(?s).*\\b" + name + "\\b.*"));
                if (!pixel) continue;
                if (ASTPrinter.printSimple(declaration.getType()).matches("(?s).*\\bconst\\b.*"))
                    throw unsupported("constant depends on the fragment position");
                dependent.add(member.getName().getName());
                assignments.add(member.getName().getName() + " = " + value + ";");
                member.setInitializer(null);
            }
        }
        return String.join(" ", assignments);
    }

    /**
     * Stage inputs and outputs become plain globals and the unit gets its work-group declaration. A compute stage has
     * no derivatives: they read zero, and implicit-LOD lookups take the base level plus their bias, as GL defines
     * outside fragment shaders (Vulkan has no implicit LOD there).
     */
    static void computeStage(TranslationUnit tree, Root root, ASTParser parser) {
        for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
            if (call.getFunctionName() == null) continue;
            String name = call.getFunctionName().getName();
            if (DERIVATIVES.contains(name)) call.replaceByAndDelete(parser.parseExpression(root,
                    "((" + ASTPrinter.printSimple(call.getParameters().getFirst()) + ") * 0.0)"));
            else if (IMPLICIT_LOD.containsKey(name)) {
                call.getFunctionName().setName(IMPLICIT_LOD.get(name));
                if (call.getParameters().size() == 2) call.getParameters().add(parser.parseExpression(root, "0.0"));
            }
        }
        for (var child : List.copyOf(tree.getChildren())) {
            if (!(child instanceof DeclarationExternalDeclaration external)
                    || !(external.getDeclaration() instanceof TypeAndInitDeclaration)) continue;
            boolean stage = root.nodeIndex.getStream(StorageQualifier.class)
                                          .anyMatch(qualifier -> qualifier.hasAncestor(external)
                                                  && (qualifier.storageType == StorageQualifier.StorageType.IN
                                                  || qualifier.storageType == StorageQualifier.StorageType.OUT));
            if (!stage) continue;
            String plain = ASTPrinter.printSimple(external)
                                     .replaceAll("layout\\s*\\([^)]*location[^)]*\\)", "")
                                     .replaceAll("\\b(in|out|flat|smooth|noperspective|centroid)\\b", "");
            tree.getChildren().set(tree.getChildren().indexOf(external), parser.parseExternalDeclaration(root, plain));
        }
        tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "layout(local_size_x = 8, local_size_y = 8) in;", "const vec2 workGroupsRender = vec2(1.0, 1.0);");
    }

    /**
     * The neighbourhood filter over the reflection slot. {@code position}/{@code normal} are view-space expressions of
     * {@code pixel} and {@code node}; {@code opaque} reads the opaque depth at {@code pixel}.
     */
    static String filter(String heads, String slot, String position, String normal, String opaque) {
        return DeferredOitProfile.resource("layer_rear_filter.glsl").replace("_FLW_REAR_HEADS", heads)
                                 .replace("_FLW_REAR_SLOT", slot).replace("_FLW_REAR_POSITION", position)
                                 .replace("_FLW_REAR_NORMAL", normal).replace("_FLW_REAR_OPAQUE", opaque);
    }
}
