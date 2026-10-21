package dev.engine_room.flywheel.backend.engine.shaderpack;

import dev.engine_room.flywheel.backend.engine.shaderpack.shader.*;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import org.lwjgl.opengl.GL20;

final class GuestTerrainShaders {
    private GuestTerrainShaders() {
    }

    static String vertex(String source, boolean authored) {
        var rewritten = GuestGlslRewriter.rewrite(GlslPreprocessor.preprocess(source));
        var result = new StringBuilder(GuestShaderTemplates.HEADER)
                .append("#extension GL_ARB_shader_draw_parameters : require\n");
        if (GuestHost.current().optifineLayout()) result.append("#define _FLW_OPTIFINE_TERRAIN\n");
        GuestShaderTemplates.copyExtensions(source, result);
        var prelude = GuestShaderTemplates.vertexPrelude(rewritten.variables(),
                authored ? GuestContractShaders.VARYINGS : null);
        result.append(prelude).append(GuestContractShaders.library(ResourceUtil.rl("shaderpack/terrain_indirect.glsl")))
              .append("uniform ivec2 _flw_terrainFallbackMaterial;\n");
        if (authored) result.append(GuestContractShaders.vertexLibrary(ResourceUtil.rl("material/default.vert")));
        result.append(GuestShaderTemplates.stripPackDirectives(rewritten.source())).append("""
                void main() {
                    TerrainDraw draw=_flw_terrainDraws[_flw_baseDraw+uint(_flw_drawId)];
                    uint stride=draw.strideWords&0xFFFFu;
                    uint word=draw.wordOffset+uint(gl_VertexID)*stride;
                    vec3 local=vec3(uintBitsToFloat(_flw_terrainVertices[word]),uintBitsToFloat(_flw_terrainVertices[word+1u]),uintBitsToFloat(_flw_terrainVertices[word+2u]));
                    // 1.12.2: preserve RenderChunk's modelview expansion around (-8,-8,-8).
                    if ((draw.strideWords&0x80000000u)==0u) local=(local+vec3(8.0))*1.000001-vec3(8.0);
                    flw_vertexPos=vec4(local+vec3(draw.x,draw.y,draw.z),1.0);
                    _flw_vertexPosOriginRel=flw_vertexPos;
                    flw_vertexId=uint(gl_VertexID);
                    _flw_packVertex=flw_vertexPos+vec4(_flw_originRelToCameraRel,0.0);
                    flw_vertexColor=unpackUnorm4x8(_flw_terrainVertices[word+3u]);
                    flw_vertexTexCoord=vec2(uintBitsToFloat(_flw_terrainVertices[word+4u]),uintBitsToFloat(_flw_terrainVertices[word+5u]));
                    uint light=_flw_terrainVertices[word+6u];
                    flw_vertexLight=vec2(light&65535u,light>>16u)/256.0;
                    flw_vertexOverlay=ivec2(0,10);
                    flw_vertexNormal=stride==14u?unpackSnorm4x8(_flw_terrainVertices[word+7u]).xyz:vec3(0,1,0);
                    vec4 material=vec4(_flw_terrainFallbackMaterial,0,0);
                    vec4 tangent=vec4(1,0,0,1);
                    vec2 midUv=flw_vertexTexCoord;
                    vec4 midBlock=vec4(0);
                    if(stride==14u) {
                        #ifdef _FLW_OPTIFINE_TERRAIN
                        midUv=vec2(uintBitsToFloat(_flw_terrainVertices[word+8u]),uintBitsToFloat(_flw_terrainVertices[word+9u]));
                        uint tangentLow=_flw_terrainVertices[word+10u],tangentHigh=_flw_terrainVertices[word+11u];
                        tangent=vec4(int(tangentLow<<16u)>>16,int(tangentLow)>>16,int(tangentHigh<<16u)>>16,int(tangentHigh)>>16);
                        uint low=_flw_terrainVertices[word+12u],high=_flw_terrainVertices[word+13u];
                        material=vec4(int(low<<16u)>>16,int(low)>>16,int(high<<16u)>>16,0);
                        #else
                        uint low=_flw_terrainVertices[word+8u],high=_flw_terrainVertices[word+9u];
                        material=vec4(int(low<<16u)>>16,int(low)>>16,int(high<<16u)>>16,int(high)>>16);
                        midUv=vec2(uintBitsToFloat(_flw_terrainVertices[word+10u]),uintBitsToFloat(_flw_terrainVertices[word+11u]));
                        tangent=unpackSnorm4x8(_flw_terrainVertices[word+12u]);
                        midBlock=round(unpackSnorm4x8(_flw_terrainVertices[word+13u])*127.0);
                        #endif
                    }
                    flw_texCoord0=vec4(flw_vertexTexCoord,0,1);
                    flw_texCoord1=vec4(flw_vertexLight*256.0,0,1);
                    flw_texCoord2=vec4(0,0,0,1);
                    flw_texCoord3=flw_texCoord2;flw_texCoord4=flw_texCoord2;flw_texCoord5=flw_texCoord2;flw_texCoord6=flw_texCoord2;flw_texCoord7=flw_texCoord2;
                """);
        for (var name : new String[]{"mc_Entity", "iris_Entity", "at_tangent", "mc_midTexCoord", "at_midBlock"}) {
            if (!rewritten.variables().containsKey(name)) continue;
            var value = switch (name) {
                case "mc_Entity", "iris_Entity" -> "material";
                case "at_tangent" -> "tangent";
                case "mc_midTexCoord" -> "vec4(midUv,0,1)";
                default -> "midBlock";
            };
            GuestVertexWrapper.input(result, rewritten.variables(), name, value);
        }
        if (authored) result.append(
                                    "_flw_unpackMaterialProperties(_flw_packedMaterial.y,flw_material);clrwl_vertexTangent=tangent/")
                            .append(GuestHost.current().tangentScale())
                            .append(";_flw_clipSlidPos=vec3(0);_flw_clipPlane=vec4(0);\n");
        return ShaderCompatibility.normalize(result.append("_flw_shader_main();\n}\n").toString(),
                GL20.GL_VERTEX_SHADER);
    }
}
