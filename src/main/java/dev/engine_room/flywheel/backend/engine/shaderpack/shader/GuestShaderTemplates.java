package dev.engine_room.flywheel.backend.engine.shaderpack.shader;

import dev.engine_room.flywheel.backend.gl.GlCompat;

import io.github.douira.glsl_transformer.GLSLLexer;
import java.util.Map;
import java.util.Set;

public final class GuestShaderTemplates {
    public static final String SHARED_VERTEX_PRELUDE = """
            invariant gl_Position;
            in vec3 _flw_aPos;
            in vec4 _flw_aColor;
            in vec2 _flw_aTexCoord;
            in vec2 _flw_aOverlay;
            in vec2 _flw_aLight;
            in vec3 _flw_aNormal;
            in vec2 _flw_aMidTexCoord;
            in ivec4 _flw_aIrisEntity;
            in vec4 _flw_aTangent;
            in vec4 _flw_aMidBlock;
            in vec4 _flw_aFaceNormal;
            uniform mat4 flw_viewMat;
            uniform mat4 flw_viewMatInverse;
            uniform mat4 flw_viewMatTranspose;
            uniform mat4 _flw_packProjection;
            uniform mat4 _flw_packProjectionInverse;
            uniform mat4 flw_modelViewProj;
            uniform mat4 flw_modelViewProjInverse;
            uniform mat3 flw_normalMatrix;
            uniform mat4 flw_textureMatrix[8];
            uniform vec3 _flw_originRelToCameraRel;
            uniform mat4 _flw_viewMatOriginRel;
            uniform bool _flw_embedded;
            uniform ivec4 _flw_overrideMaterial;
            uniform mat4 _flw_modelMatrixUniform;
            uniform mat3 _flw_normalMatrixUniform;
            vec4 _flw_vertexPosOriginRel;
            vec4 _flw_packVertex;
            vec4 flw_vertexPos;
            vec3 flw_vertexNormal;
            vec4 flw_vertexColor;
            vec2 flw_vertexTexCoord;
            ivec2 flw_vertexOverlay;
            vec2 flw_vertexLight;
            uint flw_vertexId;
            vec4 flw_texCoord0;
            vec4 flw_texCoord1;
            vec4 flw_texCoord2;
            vec4 flw_texCoord3;
            vec4 flw_texCoord4;
            vec4 flw_texCoord5;
            vec4 flw_texCoord6;
            vec4 flw_texCoord7;
            vec4 mc_Entity;
            vec4 at_tangent;
            vec4 mc_midTexCoord;
            vec4 at_midBlock;
            vec4 _flw_ftransform() {
                return _flw_packProjection*(_flw_viewMatOriginRel*_flw_vertexPosOriginRel);
            }
            """;
    public static final String HEADER = "#version " + GlCompat.MAX_GLSL_VERSION.version + " compatibility\n";

    private GuestShaderTemplates() { }

    public static void copyExtensions(String source, StringBuilder target) {
        for (var directive : GlslDirectives.scan(source)) {
            if (directive.kind() != GLSLLexer.NR_EXTENSION) continue;
            target.append(directive.text());
            if (!directive.text().endsWith("\n")) target.append('\n');
        }
    }

    public static String stripPackDirectives(String source) {
        StringBuilder result = null;
        var previous = 0;
        for (var directive : GlslDirectives.scan(source)) {
            if (directive.kind() == GLSLLexer.NR_LINE) continue;
            if (result == null) result = new StringBuilder(source.length());
            result.append(source, previous, directive.start()).append('\n');
            previous = directive.end();
        }
        return result == null ? source : result.append(source, previous, source.length()).toString();
    }

    public static String vertexPrelude(Map<String, String> inputs, String contractVaryings) {
        var ast = GlslAst.preprocessed(SHARED_VERTEX_PRELUDE);
        for (var input : inputs.entrySet()) {
            var found = ast.globals().filter(declaration -> declaration.getMembers().stream()
                    .anyMatch(member -> member.getName().getName().equals(input.getKey()))).toList();
            var declaration = input.getValue() + " " + input.getKey() + ";";
            if (found.isEmpty()) ast.prepend(declaration);
            else found.getFirst().getParent().replaceByAndDelete(ast.declaration(declaration));
        }
        if (contractVaryings != null) {
            var fields = Set.of("flw_vertexPos", "flw_vertexNormal", "flw_vertexColor", "flw_vertexTexCoord",
                    "flw_vertexOverlay", "flw_vertexLight");
            ast.globals().filter(declaration -> declaration.getMembers().stream()
                    .anyMatch(member -> fields.contains(member.getName().getName()))).toList()
                    .forEach(declaration -> declaration.getParent().detachAndDelete());
            ast.append("out " + contractVaryings);
        } else ast.append("out vec3 _flw_clipSlidPos; flat out vec4 _flw_clipPlane;");
        return ast.body();
    }
}
