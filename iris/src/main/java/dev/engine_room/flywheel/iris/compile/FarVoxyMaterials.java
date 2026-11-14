package dev.engine_room.flywheel.iris.compile;

import dev.engine_room.flywheel.backend.compile.ShaderAssembly.RawSource;
import dev.engine_room.flywheel.backend.compile.core.Compilation;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import io.github.douira.glsl_transformer.ast.traversal.ASTBaseVisitor;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps material samples on their source texture and UV tangent frame when a Voxy callback shades a far surface.
 */
final class FarVoxyMaterials {
    private static final Map<String, Integer> CHANNELS = Map.of("gtexture", 0, "blockModelAtlas", 0,
            "normals", 1, "specular", 2);
    private static final String BASIS = """
            mat3 _flw_farMaterialTbn() {
                vec3 normal = normalize(_flw_farWorldNormal);
                vec3 tangent = normalize(_flw_farWorldTangent.xyz
                        - normal * dot(_flw_farWorldTangent.xyz, normal));
                vec3 bitangent = cross(normal, tangent) * _flw_farWorldTangent.w;
                return mat3(tangent, bitangent, normal);
            }
            """;

    private FarVoxyMaterials() {
    }

    static Result patch(String source, String samplerLayout) {
        var parser = new SingleASTTransformer<JobParameters>();
        var layout = parser.parseSeparateTranslationUnit("#version 460 core\n" + samplerLayout);
        var tree = parser.parseSeparateTranslationUnit("#version 460 core\n" + source);
        var root = tree.getRoot();
        var reachable = FarVoxyNormals.reachable(root);
        var materialSamplers = Stream.of(layout.getRoot(), root)
                                     .flatMap(declarations -> declarations.nodeIndex.getStream(DeclarationMember.class))
                                     .filter(member -> !member.hasAncestor(FunctionDefinition.class))
                                     .filter(member -> CHANNELS.containsKey(member.getName().getName()))
                                     .filter(member -> ASTPrinter.printSimple(
                                             member.getAncestor(TypeAndInitDeclaration.class)
                                                   .getType().getTypeSpecifier()).strip().equals("sampler2D"))
                                     .map(member -> member.getName().getName()).collect(Collectors.toSet());
        boolean sampled = false;
        for (var reference : root.nodeIndex.getStream(ReferenceExpression.class).toList()) {
            String name = reference.getIdentifier().getName();
            var function = reference.getAncestor(FunctionDefinition.class);
            if (!materialSamplers.contains(name) || !reachable.contains(function)
                    || function.getFunctionPrototype().getParameters().stream()
                               .anyMatch(parameter -> parameter.getName() != null && parameter.getName().getName()
                                                                                              .equals(name))) continue;
            reference.replaceByAndDelete(parser.parseExpression(root,
                    "_flw_farMaterialSampler(" + CHANNELS.get(name) + "u)"));
            sampled = true;
        }
        boolean basis = false;
        if (sampled) {
            for (var call : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
                if (call.getFunctionName() == null || !call.getFunctionName().getName().equals("tbnNormal")
                        || call.getParameters().size() != 1
                        || !reachable.contains(call.getAncestor(FunctionDefinition.class))) continue;
                var argument = call.getParameters().getFirst();
                String matrix = viewMatrix(argument);
                String replacement = matrix == null ? "_flw_farMaterialTbn()"
                        : "mat3(" + matrix + ") * _flw_farMaterialTbn()";
                call.replaceByAndDelete(parser.parseExpression(root, replacement));
                basis = true;
            }
        }
        if (basis) {
            var library = new Compilation();
            library.version(GlslVersion.V460);
            library.appendComponent(new RawSource("far material tangent basis", BASIS));
            var declarations = parser.parseTranslationUnit(root, library.assembledSource());
            tree.injectNodes(ASTInjectionPoint.BEFORE_FUNCTIONS,
                    declarations.getChildren().stream().map(node -> node.cloneInto(root)).toList());
            declarations.detachAndDelete();
        }
        return new Result(tree.getChildren().stream().map(ASTPrinter::printSimple).collect(Collectors.joining("\n")),
                sampled);
    }

    private static String viewMatrix(ASTNode node) {
        String[] matrix = {null};
        new ASTBaseVisitor<Void>() {
            @Override
            public Void visitReferenceExpression(ReferenceExpression reference) {
                String name = reference.getIdentifier().getName();
                if (Set.of("vxModelView", "gbufferModelView").contains(name)) matrix[0] = name;
                return null;
            }

            @Override
            public Void visitFunctionCallExpression(FunctionCallExpression call) {
                if (call.getFunctionName() != null && call.getFunctionName().getName().equals("player_view")) {
                    matrix[0] = "vxModelView";
                }
                return super.visitFunctionCallExpression(call);
            }
        }.visit(node);
        return matrix[0];
    }

    record Result(String source, boolean usesMaterialMaps) {
    }
}
