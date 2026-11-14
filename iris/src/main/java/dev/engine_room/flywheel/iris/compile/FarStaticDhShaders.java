package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.engine.far.FarStaticDraw;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import io.github.douira.glsl_transformer.ast.traversal.ASTBaseVisitor;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces only DH's mesh decoder and texture-less colour input; the pack retains its terrain stages.
 */
public final class FarStaticDhShaders {
    private static final Set<String> ATTRIBUTES = Set.of("vPosition", "iris_color", "irisExtra");
    private static final Map<String, Integer> MATERIAL_SAMPLERS = Map.of("gtexture", 0, "blockModelAtlas", 0,
            "normals", 1, "specular", 2);

    private FarStaticDhShaders() {
    }

    public static Stages patch(String vertexSource, String fragmentSource) {
        var colors = new LinkedHashMap<String, String>();
        var vertex = GuestShaders.versionedTransformer();
        vertex.setTransformation((tree, root) -> {
            minimumVersion(tree);
            GuestShaders.preamble(vertex, tree,
                    compilation -> compilation.requireExtension("GL_ARB_shader_draw_parameters"));
            var dependencies = new HashSet<>(Set.of("_vert_color", "iris_Color", "gl_Color", "iris_color"));
            var assignments = new ArrayList<BinaryExpression>();
            new ASTBaseVisitor<Void>() {
                @Override
                public Void visitBinaryExpression(BinaryExpression expression) {
                    switch (expression.getExpressionType()) {
                        case ASSIGNMENT, MULTIPLICATION_ASSIGNMENT, DIVISION_ASSIGNMENT, ADDITION_ASSIGNMENT,
                             SUBTRACTION_ASSIGNMENT -> assignments.add(expression);
                        default -> {
                        }
                    }
                    return super.visitBinaryExpression(expression);
                }
            }.visit(tree);
            boolean changed;
            do {
                changed = false;
                for (var member : root.nodeIndex.getStream(DeclarationMember.class).toList()) {
                    if (member.getInitializer() instanceof ExpressionInitializer initializer
                            && references(initializer, dependencies)) {
                        changed |= dependencies.add(member.getName().getName());
                    }
                }
                for (var expression : assignments) {
                    if (references(expression.getRight(), dependencies)) {
                        changed |= dependencies.addAll(references(expression.getLeft()));
                    }
                }
                for (var function : root.nodeIndex.getStream(FunctionDefinition.class).toList()) {
                    if (references(function.getBody(), dependencies)) {
                        changed |= dependencies.add(function.getFunctionPrototype().getName().getName());
                    }
                }
            } while (changed);
            for (var external : tree.getChildren()) {
                if (!(external instanceof DeclarationExternalDeclaration declaration)
                        || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)) continue;
                for (var member : typed.getMembers()) {
                    String name = member.getName().getName();
                    if (ATTRIBUTES.contains(name)) typed.getType().setTypeQualifier(null);
                    String type = ASTPrinter.printSimple(typed.getType().getTypeSpecifier()).strip();
                    var qualifier = typed.getType().getTypeQualifier();
                    boolean output = qualifier != null && qualifier.getParts().stream().anyMatch(part ->
                            part instanceof StorageQualifier storage
                                    && storage.storageType == StorageQualifier.StorageType.OUT);
                    if (output && dependencies.contains(name) && Set.of("vec3", "vec4").contains(type)) {
                        colors.put(name, type);
                    }
                }
            }
            for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (call.getFunctionName() != null && call.getFunctionName().getName().equals("_vert_init")) {
                    call.replaceByAndDelete(vertex.parseExpression(root, "_flw_staticDhInit()"));
                }
            }
            var library = new Compilation();
            FarStaticDraw.vertex(library, false, 0, 1, 2);
            library.appendComponent(new RawSource("captured DH decoder", """
                    uniform uint _flw_staticFlags;
                    void _flw_staticDhInit() {
                        _flw_staticInit();
                        _vert_position = _flw_staticWorld;
                        _vert_normal = _flw_staticNormal;
                        _vert_color = _flw_staticColor;
                        _vert_tex_light_coord = clamp(_flw_staticLight + 1.0 / 32.0,
                                vec2(0.5 / 16.0), vec2(15.5 / 16.0));
                        if ((_flw_staticFlags & 2u) == 0u) _vert_tex_light_coord = vec2(15.5 / 16.0);
                        dhMaterialId = int((_flw_staticMaterial >> 16u) & 15u);
                        iris_vBlockPos = _flw_staticWorld;
                        iris_TexId = uvec2(0u);
                    }
                    """));
            inject(vertex, tree, ASTInjectionPoint.BEFORE_FUNCTIONS, library);
        });
        String patchedVertex = vertex.transform(GuestShaders.shiftBufferBindings(vertexSource));
        if (colors.isEmpty())
            throw new IllegalStateException("DH terrain has no colour varying derived from _vert_color");
        boolean[] maps = {false};
        var fragment = GuestShaders.versionedTransformer();
        fragment.setTransformation((tree, root) -> {
            minimumVersion(tree);
            FarVoxyNormals.patchDhGeometry(fragment, tree);
            var reachable = FarVoxyNormals.reachable(root, "main");
            var samplers = new HashSet<String>();
            for (var member : root.nodeIndex.getStream(DeclarationMember.class).toList()) {
                if (!member.hasAncestor(FunctionDefinition.class) && MATERIAL_SAMPLERS.containsKey(
                        member.getName().getName())
                        && ASTPrinter.printSimple(member.getAncestor(TypeAndInitDeclaration.class)
                                                        .getType().getTypeSpecifier()).strip().equals("sampler2D"))
                    samplers.add(member.getName().getName());
            }
            boolean albedoSample = false;
            for (var reference : root.nodeIndex.getStream(ReferenceExpression.class).toList()) {
                String name = reference.getIdentifier().getName();
                if (!samplers.contains(name) || !reachable.contains(reference.getAncestor(FunctionDefinition.class)))
                    continue;
                int channel = MATERIAL_SAMPLERS.get(name);
                var call = reference.getAncestor(FunctionCallExpression.class);
                if (call != null && call.getFunctionName() != null
                        && Set.of("texture", "textureLod", "textureGrad", "textureOffset", "textureLodOffset",
                        "textureGradOffset").contains(call.getFunctionName().getName()) && call.getParameters()
                                                                                               .size() >= 2
                        && (reference == call.getParameters().getFirst()
                        || reference.hasAncestor(call.getParameters().getFirst()))) {
                    call.getParameters().get(1).replaceByAndDelete(fragment.parseExpression(root, "_flw_staticUv"));
                    if (call.getFunctionName().getName().startsWith("textureGrad")) {
                        call.getParameters().get(2)
                            .replaceByAndDelete(fragment.parseExpression(root, "dFdx(_flw_staticUv)"));
                        call.getParameters().get(3)
                            .replaceByAndDelete(fragment.parseExpression(root, "dFdy(_flw_staticUv)"));
                    }
                }
                reference.replaceByAndDelete(
                        fragment.parseExpression(root, "_flw_farMaterialSampler(" + channel + "u)"));
                maps[0] = true;
                albedoSample |= channel == 0;
            }
            StringBuilder initialize = new StringBuilder(
                    "_flw_staticClip();\nif (_flw_staticTerrainOccluded()) discard;\n");
            var library = new Compilation();
            library.appendComponent(new RawSource("captured DH appearance", FarStaticDraw.FRAGMENT));
            library.appendComponent(new RawSource("opaque DH terrain depth", FarStaticDraw.TERRAIN_DEPTH_FRAGMENT));
            int colorInputs = 0;
            for (var external : List.copyOf(tree.getChildren())) {
                if (!(external instanceof DeclarationExternalDeclaration declaration)
                        || !(declaration.getDeclaration() instanceof TypeAndInitDeclaration typed)) continue;
                for (var member : typed.getMembers()) {
                    String name = member.getName().getName();
                    if (!colors.containsKey(name)) continue;
                    var qualifier = typed.getType().getTypeQualifier();
                    if (qualifier == null || qualifier.getParts().stream().noneMatch(part ->
                            part instanceof StorageQualifier storage
                                    && storage.storageType == StorageQualifier.StorageType.IN)) continue;
                    String type = colors.get(name);
                    String mutable = "_flw_staticLit_" + name;
                    for (var reference : root.nodeIndex.getStream(ReferenceExpression.class).toList()) {
                        if (reference.getIdentifier().getName().equals(name))
                            reference.getIdentifier().setName(mutable);
                    }
                    library.appendComponent(new RawSource("mutable DH colour " + name, type + " " + mutable + ";"));
                    String input = type.equals("vec3") ? "vec4(" + name + ", _flw_staticColor.a)" : name;
                    String color = albedoSample ? input : "_flw_staticSampleColor(" + input + ")";
                    if (type.equals("vec3")) color += ".rgb";
                    initialize.append(mutable).append(" = ").append(color).append(";\n");
                    colorInputs++;
                }
            }
            if (colorInputs == 0)
                throw new IllegalStateException("DH fragment does not consume its vertex colour varying");
            if (maps[0]) {
                GuestShaders.preamble(fragment, tree,
                        compilation -> compilation.requireExtension("GL_ARB_bindless_texture"));
                library.appendComponent(new RawSource("captured DH material maps", FarMaterialTextures.SHADER));
            }
            root.rename("main", "_flw_staticNativeMain");
            inject(fragment, tree, ASTInjectionPoint.BEFORE_DECLARATIONS, library);
            var entry = new Compilation();
            entry.appendComponent(new RawSource("captured DH entry", "void main() {\n"
                    + initialize + "_flw_staticNativeMain();\n}"));
            inject(fragment, tree, ASTInjectionPoint.END, entry);
        });
        String patchedFragment = fragment.transform(
                GuestShaders.shiftBufferBindings(FarDhShaders.fragment(fragmentSource)));
        return new Stages(patchedVertex, patchedFragment, maps[0]);
    }

    private static void minimumVersion(TranslationUnit tree) {
        if (tree.getVersionStatement().version.compareTo(Version.fromNumber(430)) < 0) {
            tree.getVersionStatement().version = Version.fromNumber(430);
        }
    }

    private static boolean references(ASTNode node, Set<String> names) {
        return references(node).stream().anyMatch(names::contains);
    }

    private static Set<String> references(ASTNode node) {
        var names = new HashSet<String>();
        new ASTBaseVisitor<Void>() {
            @Override
            public Void visitReferenceExpression(ReferenceExpression reference) {
                names.add(reference.getIdentifier().getName());
                return null;
            }

            @Override
            public Void visitFunctionCallExpression(FunctionCallExpression call) {
                if (call.getFunctionName() != null) names.add(call.getFunctionName().getName());
                return super.visitFunctionCallExpression(call);
            }
        }.visit(node);
        return names;
    }

    private static void inject(SingleASTTransformer<JobParameters> parser,
                               TranslationUnit tree, ASTInjectionPoint point, Compilation source) {
        source.version(GlslVersion.V430);
        var library = parser.parseTranslationUnit(tree.getRoot(), source.assembledSource());
        tree.injectNodes(point,
                library.getChildren().stream().map(node -> node.cloneInto(tree.getRoot())).toList());
        library.detachAndDelete();
    }

    public record Stages(String vertex, String fragment, boolean materialMaps) {
    }
}
