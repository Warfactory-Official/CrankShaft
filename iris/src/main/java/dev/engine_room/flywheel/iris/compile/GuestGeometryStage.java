package dev.engine_room.flywheel.iris.compile;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.*;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.LayoutDefaults;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.NamedLayoutQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.FunctionPrototype;
import io.github.douira.glsl_transformer.ast.node.type.specifier.TypeReference;
import io.github.douira.glsl_transformer.ast.node.type.struct.StructSpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;

import java.util.*;

final class GuestGeometryStage {
    static final String PREFIX = "_flw_gs_";

    private GuestGeometryStage() {
    }

    static Map<String, String> sharedDeclarations(TranslationUnit tree) {
        Map<String, String> result = new HashMap<>();
        for (var external : tree.getChildren()) {
            if (!(external instanceof DeclarationExternalDeclaration declaration)) continue;
            if (declaration.getDeclaration() instanceof InterfaceBlockDeclaration block && shared(block)) {
                result.put("block:" + block.getBlockName().getName(), ASTPrinter.printSimple(block));
            } else if (declaration.getDeclaration() instanceof TypeAndInitDeclaration typed) {
                if (GuestTerrainMeshShaders.storage(typed.getType().getTypeQualifier(),
                        StorageQualifier.StorageType.UNIFORM)) {
                    for (var member : typed.getMembers())
                        result.put("uniform:" + member.getName().getName(), uniformType(typed, member));
                } else if (typed.getMembers().isEmpty() && typed.getType()
                                                                .getTypeSpecifier() instanceof StructSpecifier struct
                        && struct.getName() != null) {
                    result.put("type:" + struct.getName().getName(), ASTPrinter.printSimple(typed));
                }
            }
        }
        return result;
    }

    static Result build(String source, Map<String, String> vertexDeclarations) {
        var parser = GuestTerrainMeshShaders.transformer();
        var stage = new GuestTerrainMeshShaders.Stage();
        stage.geometry = true;
        int[] maxVertices = {0};
        boolean[] triangles = {false}, strip = {false};
        parser.setTransformation((tree, root) -> {
            for (var external : List.copyOf(tree.getChildren())) {
                if (external instanceof DeclarationExternalDeclaration declaration
                        && declaration.getDeclaration() instanceof VariableDeclaration variable && variable.getNames()
                                                                                                           .isEmpty()) {
                    boolean input = GuestTerrainMeshShaders.storage(variable.getTypeQualifier(),
                            StorageQualifier.StorageType.IN);
                    if (input || GuestTerrainMeshShaders.storage(variable.getTypeQualifier(),
                            StorageQualifier.StorageType.OUT)) {
                        for (var part : variable.getTypeQualifier().getParts())
                            if (part instanceof LayoutQualifier layout) {
                                layout(layout, input, maxVertices, triangles, strip);
                            }
                        external.detachAndDelete();
                        continue;
                    }
                }
                if (external instanceof LayoutDefaults layout && (layout.mode == LayoutDefaults.LayoutMode.IN
                        || layout.mode == LayoutDefaults.LayoutMode.OUT)) {
                    layout(layout.getQualifier(), layout.mode == LayoutDefaults.LayoutMode.IN, maxVertices, triangles,
                            strip);
                    external.detachAndDelete();
                } else if (external instanceof DeclarationExternalDeclaration declaration) {
                    if (declaration.getDeclaration() instanceof InterfaceBlockDeclaration block && shared(block)) {
                        String existing = vertexDeclarations.get("block:" + block.getBlockName().getName());
                        if (existing != null) {
                            requireMatch(block.getBlockName().getName(), existing, ASTPrinter.printSimple(block));
                            external.detachAndDelete();
                        }
                    } else if (declaration.getDeclaration() instanceof TypeAndInitDeclaration typed) {
                        if (GuestTerrainMeshShaders.storage(typed.getType().getTypeQualifier(),
                                StorageQualifier.StorageType.UNIFORM)) {
                            for (var member : List.copyOf(typed.getMembers())) {
                                String existing = vertexDeclarations.get("uniform:" + member.getName().getName());
                                if (existing != null) {
                                    requireMatch(member.getName().getName(), existing, uniformType(typed, member));
                                    member.detachAndDelete();
                                }
                            }
                            if (typed.getMembers().isEmpty()) external.detachAndDelete();
                        } else if (typed.getMembers().isEmpty() && typed.getType()
                                                                        .getTypeSpecifier() instanceof StructSpecifier struct
                                && struct.getName() != null) {
                            String existing = vertexDeclarations.get("type:" + struct.getName().getName());
                            if (ASTPrinter.printSimple(typed).equals(existing)) external.detachAndDelete();
                        }
                    }
                }
            }
            if (!triangles[0] || !strip[0] || maxVertices[0] < 3 || maxVertices[0] > 16) {
                throw new IllegalStateException(
                        "Mesh terrain requires a bounded triangles-to-triangle-strip geometry stage: triangles="
                                + triangles[0] + ", strip=" + strip[0] + ", max_vertices=" + maxVertices[0]);
            }
            if (root.identifierIndex.has("EmitStreamVertex") || root.identifierIndex.has("EndStreamPrimitive")) {
                throw new IllegalStateException("Mesh terrain does not support geometry output streams");
            }
            Set<String> names = new HashSet<>();
            for (var external : tree.getChildren()) {
                if (!(external instanceof DeclarationExternalDeclaration declaration)) continue;
                if (declaration.getDeclaration() instanceof TypeAndInitDeclaration typed
                        && !GuestTerrainMeshShaders.storage(typed.getType().getTypeQualifier(),
                        StorageQualifier.StorageType.UNIFORM)) {
                    for (var member : typed.getMembers()) names.add(member.getName().getName());
                    if (typed.getType()
                             .getTypeSpecifier() instanceof StructSpecifier struct && struct.getName() != null) {
                        names.add(struct.getName().getName());
                    }
                } else if (declaration.getDeclaration() instanceof InterfaceBlockDeclaration block && !shared(block)
                        && block.getVariableName() != null) names.add(block.getVariableName().getName());
            }
            root.nodeIndex.getStream(FunctionPrototype.class)
                          .forEach(function -> names.add(function.getName().getName()));
            for (String name : names) rename(root, name, PREFIX + name);
            rename(root, "gl_Position", "_flw_geometryPosition");
            rename(root, "gl_in", "_flw_geometryIn");
            rename(root, "gl_PrimitiveIDIn", "_flw_geometryPrimitiveIn");
            rename(root, "gl_PrimitiveID", "_flw_geometryPrimitiveOut");
            rename(root, "gl_InvocationID", "_flw_geometryInvocation");
            rename(root, "gl_Layer", "_flw_geometryLayer");
            rename(root, "gl_ViewportIndex", "_flw_geometryViewport");
            rename(root, "EmitVertex", "_flw_emitGeometry");
            rename(root, "EndPrimitive", "_flw_endGeometry");
            GuestTerrainMeshShaders.transformInterface(parser, tree, stage, true);
            tree.setVersionStatement(null);
        });
        return new Result(parser.transform(source), stage, maxVertices[0]);
    }

    private static void rename(Root root, String old, String replacement) {
        for (Identifier identifier : root.identifierIndex.getStream(old).toList()) {
            var parent = identifier.getParent();
            if (parent instanceof ReferenceExpression || parent instanceof DeclarationMember || parent instanceof FunctionParameter
                    || parent instanceof TypeReference || parent instanceof FunctionPrototype || parent instanceof StructSpecifier
                    || parent instanceof FunctionCallExpression
                    || parent instanceof InterfaceBlockDeclaration block && identifier == block.getVariableName()) {
                identifier.setName(replacement);
            }
        }
    }

    private static void layout(LayoutQualifier layout, boolean input, int[] maxVertices, boolean[] triangles,
                               boolean[] strip) {
        for (var part : layout.getParts()) {
            if (!(part instanceof NamedLayoutQualifierPart named))
                throw new IllegalStateException("Unknown geometry layout");
            String name = named.getName().getName();
            switch (name) {
                case "triangles" -> triangles[0] = input;
                case "triangle_strip" -> strip[0] = !input;
                case "max_vertices" ->
                        maxVertices[0] = Math.toIntExact(((LiteralExpression) named.getExpression()).getInteger());
                case "invocations" -> {
                    if (((LiteralExpression) named.getExpression()).getInteger() != 1) {
                        throw new IllegalStateException(
                                "Mesh geometry translation requires one invocation per primitive");
                    }
                }
                default -> throw new IllegalStateException("Unsupported geometry layout " + name);
            }
        }
    }

    private static String uniformType(TypeAndInitDeclaration typed, DeclarationMember member) {
        return ASTPrinter.printSimple(typed.getType())
                + (member.getArraySpecifier() == null ? "" : ASTPrinter.printSimple(member.getArraySpecifier()))
                + (member.getInitializer() == null ? "" : " = " + ASTPrinter.printSimple(member.getInitializer()));
    }

    private static boolean shared(InterfaceBlockDeclaration block) {
        return GuestTerrainMeshShaders.storage(block.getTypeQualifier(), StorageQualifier.StorageType.UNIFORM)
                || GuestTerrainMeshShaders.storage(block.getTypeQualifier(), StorageQualifier.StorageType.BUFFER);
    }

    private static void requireMatch(String name, String vertex, String geometry) {
        if (!vertex.equals(geometry))
            throw new IllegalStateException("Different vertex/geometry uniform declarations for " + name);
    }

    record Result(String body, GuestTerrainMeshShaders.Stage stage, int maxVertices) {
    }
}
