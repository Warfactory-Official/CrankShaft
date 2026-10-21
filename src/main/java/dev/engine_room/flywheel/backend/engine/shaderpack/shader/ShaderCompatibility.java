package dev.engine_room.flywheel.backend.engine.shaderpack.shader;

import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType;
import org.lwjgl.opengl.GL20;

public final class ShaderCompatibility {
    private ShaderCompatibility() { }

    public static String preserveVersion(String source) {
        // Resolve native feature branches before a wrapper raises the surrounding GLSL version.
        return GlslPreprocessor.preprocess(source);
    }

    public static String normalize(String source, int shaderType) {
        if (shaderType != GL20.GL_VERTEX_SHADER && shaderType != GL20.GL_FRAGMENT_SHADER) return source;
        var ast = GlslAst.parse(source);
        var version = ast.tree.getVersionStatement();
        var glsl = version == null ? 110 : version.version.number;
        var vertex = shaderType == GL20.GL_VERTEX_SHADER;
        for (var declaration : ast.globals().toList()) {
            var qualifiers = declaration.getType().getTypeQualifier();
            if (qualifiers == null) continue;
            for (var part : qualifiers.getParts()) {
                if (!(part instanceof StorageQualifier storage)) continue;
                // AUSM's injected declarations and the pack must use the same GLSL interface dialect.
                if (glsl >= 130 && storage.storageType == StorageType.VARYING)
                    storage.storageType = vertex ? StorageType.OUT : StorageType.IN;
                else if (glsl >= 130 && vertex && storage.storageType == StorageType.ATTRIBUTE)
                    storage.storageType = StorageType.IN;
                else if (glsl < 130 && storage.storageType == (vertex ? StorageType.OUT : StorageType.IN))
                    storage.storageType = StorageType.VARYING;
            }
        }
        return ast.source();
    }
}
