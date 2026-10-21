package dev.engine_room.flywheel.backend.engine.shaderpack.shader;

import java.util.Map;

public final class GuestGlslRewriter {
    private static final Map<String, String> SUBSTITUTIONS = Map.ofEntries(
            Map.entry("gl_Vertex", "_flw_packVertex"),
            Map.entry("gl_Normal", "flw_vertexNormal"),
            Map.entry("gl_Color", "flw_vertexColor"),
            Map.entry("gl_ModelViewMatrix", "flw_viewMat"),
            Map.entry("gl_ModelViewMatrixInverse", "flw_viewMatInverse"),
            Map.entry("gl_ModelViewMatrixTranspose", "flw_viewMatTranspose"),
            Map.entry("gl_ProjectionMatrix", "_flw_packProjection"),
            Map.entry("gl_ProjectionMatrixInverse", "_flw_packProjectionInverse"),
            Map.entry("gl_ModelViewProjectionMatrix", "flw_modelViewProj"),
            Map.entry("gl_ModelViewProjectionMatrixInverse", "flw_modelViewProjInverse"),
            Map.entry("gl_NormalMatrix", "flw_normalMatrix"),
            Map.entry("gl_TextureMatrix", "flw_textureMatrix"),
            Map.entry("gl_MultiTexCoord0", "flw_texCoord0"),
            Map.entry("gl_MultiTexCoord1", "flw_texCoord1"),
            Map.entry("gl_MultiTexCoord2", "flw_texCoord2"),
            Map.entry("gl_MultiTexCoord3", "flw_texCoord3"),
            Map.entry("gl_MultiTexCoord4", "flw_texCoord4"),
            Map.entry("gl_MultiTexCoord5", "flw_texCoord5"),
            Map.entry("gl_MultiTexCoord6", "flw_texCoord6"),
            Map.entry("gl_MultiTexCoord7", "flw_texCoord7"),
            
            Map.entry("ausm_ChunkOffsetInstanced", "vec3(0.0)"),
            Map.entry("a_Offset", "vec3(0.0)"));

    private GuestGlslRewriter() {
    }

    public static GlslInterface.Rewritten rewrite(String source) {
        var ast = GlslAst.preprocessed(source);
        var inputs = GlslInterface.vertexInputs(ast);
        var types = inputs;
        types.remove("ausm_ChunkOffsetInstanced");
        types.remove("a_Offset");
        ast.references(SUBSTITUTIONS);
        ast.renameFunction("ftransform", "_flw_ftransform");
        ast.renameFunction("main", "_flw_shader_main");
        return new GlslInterface.Rewritten(ast.source(), types);
    }
}
