package dev.engine_room.flywheel.backend.engine;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.GlslAst;
import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.Profile;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.VersionStatement;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExtensionDirective;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.util.Type;

import java.util.Map;

final class NativeChunkShader {
    private NativeChunkShader() { }

    static String transform(String vertex, String fragment, String oit, int version) {
        var nativeAst = GlslAst.preprocessed(fragment);
        validate(nativeAst);
        var color = captureColor(nativeAst);
        nativeAst.function("main");
        nativeAst.renameFunction("main", "_flw_nativeMain");

        var ast = GlslAst.preprocessed(oit);
        var shader = ast.tree;
        ast.function("main").detachAndDelete();
        ast.globals().filter(declaration -> GlslAst.storage(declaration, StorageType.IN)).toList()
                .forEach(declaration -> declaration.getParent().detachAndDelete());
        ast.inject(nativeAst, ASTInjectionPoint.BEFORE_DECLARATIONS, ExtensionDirective.class::isInstance);
        ast.inject(nativeAst, ASTInjectionPoint.BEFORE_FUNCTIONS, _ -> true);
        shader.injectNode(ASTInjectionPoint.END, ast.declaration("""
                void main() {
                    _flw_nativeMain();
                    if (%s.a < 1e-3) discard;
                    _flw_writeChunkOit(%s);
                }
                """.formatted(color, color)));
        var nativeVersion = GlslAst.preprocessed(vertex).tree.getVersionStatement();
        shader.setVersionStatement(new VersionStatement(Version.fromNumber(version),
                nativeVersion == null ? Profile.COMPATIBILITY : nativeVersion.getNormalizedProfile()));
        shader.getRoot().nodeIndex.getStream(StorageQualifier.class)
                .filter(qualifier -> qualifier.storageType == StorageType.VARYING)
                .forEach(qualifier -> qualifier.storageType = StorageType.IN);
        return ast.source();
    }

    private static void validate(GlslAst ast) {
        // Shader-pack G-buffers use authored terrain contracts; this adapter only accepts forward colour shaders.
        var root = ast.tree.getRoot();
        var blockTexture = ast.globals().anyMatch(declaration -> GlslAst.storage(declaration, StorageType.UNIFORM)
                && declaration.getMembers().stream().anyMatch(member -> member.getName().getName().equals("u_BlockTex")));
        var sideEffects = root.nodeIndex.getStream(StorageQualifier.class)
                .anyMatch(qualifier -> qualifier.storageType == StorageType.BUFFER)
                || ast.references("gl_FragData") || ast.references("gl_FragDepth")
                || root.nodeIndex.getStream(FunctionCallExpression.class).anyMatch(call -> {
                    var name = call.getFunctionName();
                    return name != null && (name.getName().equals("imageStore")
                            || name.getName().startsWith("atomic") || name.getName().startsWith("imageAtomic"));
                });
        if (!blockTexture || sideEffects || root.nodeIndex.getStream(Identifier.class)
                .anyMatch(identifier -> identifier.getName().startsWith("_flw_")))
            throw new UnsupportedOperationException("Unvalidated native chunk fragment contract");
    }

    private static String captureColor(GlslAst ast) {
        var outputs = ast.globals().filter(declaration -> GlslAst.storage(declaration, StorageType.OUT)).toList();
        var legacy = ast.references("gl_FragColor");
        if (legacy) {
            if (!outputs.isEmpty()) throw new UnsupportedOperationException("Native chunk shader mixes colour outputs");
            ast.references(Map.of("gl_FragColor", "_flw_nativeColor"));
            ast.tree.injectNode(ASTInjectionPoint.BEFORE_FUNCTIONS, ast.declaration("vec4 _flw_nativeColor;"));
            return "_flw_nativeColor";
        } else {
            if (outputs.size() != 1) throw new UnsupportedOperationException("Native chunk shader needs one colour output");
            var output = outputs.getFirst();
            if (output.getMembers().size() != 1
                    || !output.getMembers().getFirst().getName().getName().equals("fragColor")
                    || output.getMembers().getFirst().getArraySpecifier() != null
                    || output.getMembers().getFirst().getInitializer() != null
                    || !(output.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier numeric)
                    || numeric.type != Type.F32VEC4 || numeric.getArraySpecifier() != null)
                throw new UnsupportedOperationException("Native chunk shader needs a scalar vec4 fragColor output");
            // Keep the name so native helpers and local variables retain GLSL's original scope resolution.
            output.getParent().replaceByAndDelete(ast.declaration("vec4 fragColor;"));
            return "fragColor";
        }
    }

}
