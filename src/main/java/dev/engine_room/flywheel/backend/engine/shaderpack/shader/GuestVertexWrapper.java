package dev.engine_room.flywheel.backend.engine.shaderpack.shader;

import dev.engine_room.flywheel.backend.engine.shaderpack.GuestRendering;

import org.lwjgl.opengl.GL20;

import java.util.Map;

public final class GuestVertexWrapper {
    private GuestVertexWrapper() {
    }

    public static String wrapVertex(String struct, String unpack, String instanceVertex,
                                    String packVertex, Map<String, String> inputs, boolean crumbling,
                                    String materialLibrary, String contractVaryings, boolean embedded,
                                    boolean vertexLight,
                                    boolean indirect) {
        var instanceIndex = crumbling ? "_flw_draw.baseInstance"
                : "_flw_instanceIndices[_flw_bases.y+_flw_draw.baseInstance+uint(gl_InstanceID)]";
        var instanceLoad = indirect ? """
                MeshDrawCommand _flw_draw=_flw_drawCommands[_flw_baseDraw+uint(_flw_drawId)];
                _flw_baseVertex=_flw_draw.vertexOffset;
                #ifdef FLW_EMBEDDED
                _flw_unpackMatrices(_flw_matrices[_flw_draw.matrixIndex],_flw_modelMatrix,_flw_normalMatrix);
                #endif
                _flw_instanceType=_flw_draw.instanceType;
                uvec4 _flw_bases=_flw_typeBases[_flw_instanceType];
                """ + "uint value=_flw_bases.x+" + instanceIndex + "*_flw_bases.z;\n"
                : "FlwInstance value=_flw_unpackInstance(_flw_baseInstance+gl_InstanceID);\n";
        var matrixInit = indirect ? "" : """
                #ifdef FLW_EMBEDDED
                _flw_modelMatrix=_flw_modelMatrixUniform;
                _flw_normalMatrix=_flw_normalMatrixUniform;
                #endif
                """;
        var source = new StringBuilder(GuestShaderTemplates.HEADER);
        if (indirect) source.append("#extension GL_ARB_shader_draw_parameters : require\n");
        GuestShaderTemplates.copyExtensions(packVertex, source);
        if (embedded) source.append("#define FLW_EMBEDDED\n");
        var prelude = GuestShaderTemplates.vertexPrelude(inputs, contractVaryings);
        source.append(prelude).append(indirect ? "uint _flw_baseVertex;\n" : "uniform int _flw_baseInstance;\nuniform uint _flw_baseVertex;\n")
              .append(materialLibrary).append(struct).append(unpack).append(instanceVertex);
        if (crumbling) source.append("""
                vec2 _flw_crumblingUv(vec3 position, vec3 normal) {
                    vec3 axis=abs(normal);
                    vec2 uv;
                    if (axis.y>=axis.x && axis.y>=axis.z) uv=normal.y>0.0?position.xz:vec2(position.x,-position.z);
                    else if (axis.x>=axis.z) uv=normal.x>0.0?vec2(position.z,-position.y):-position.zy;
                    else uv=normal.z>0.0?vec2(position.x,-position.y):-position.xy;
                    // Per-vertex wrapping collapses UVs on faces spanning whole blocks; the repeating texture wraps per fragment.
                    return uv;
                }
                """);
        source.append(GuestShaderTemplates.stripPackDirectives(packVertex)).append("""
                void _flw_layout(vec3 position,vec3 normal,vec2 uv) {
                    _flw_unpackMaterialProperties(_flw_packedMaterial.y,flw_material);
                    _flw_clipSlidPos=vec3(0.0);
                    _flw_clipPlane=vec4(0.0);
                    flw_vertexPos=vec4(position,1.0);
                    flw_vertexNormal=normal;
                    flw_vertexColor=_flw_aColor;
                    flw_vertexTexCoord=uv;
                    flw_vertexLight=(_flw_aLight+8.0)/256.0;
                    flw_vertexOverlay=ivec2(_flw_aOverlay);
                    flw_vertexId=uint(gl_VertexID)-_flw_baseVertex;
                }
                void _flw_transform(%s value) {
                    %s
                    %s(value);
                    flw_materialVertex();
                    #ifdef FLW_EMBEDDED
                    flw_vertexPos=_flw_modelMatrix*flw_vertexPos;
                    flw_vertexNormal=_flw_normalMatrix*flw_vertexNormal;
                    #endif
                }
                void main() {
                    %s
                    vec3 localNormal=dot(_flw_aFaceNormal.xyz,_flw_aFaceNormal.xyz)>0.0?_flw_aFaceNormal.xyz:_flw_aNormal;
                    vec4 guestTangent=vec4(0.0);
                    vec2 guestMidUv=_flw_aMidTexCoord;
                    vec4 guestMidBlock=vec4(0.0);
                """.formatted(indirect ? "uint" : "FlwInstance", matrixInit, indirect ? "_flw_instanceVertexUber" : "flw_instanceVertex", instanceLoad));
        var proxy = contractVaryings != null || inputs.containsKey("at_tangent") || inputs.containsKey(
                "at_midBlock") || inputs.containsKey("mc_midTexCoord");
        if (proxy) source.append("""
                    vec3 centre=_flw_aPos+_flw_aMidBlock.xyz/64.0;
                    _flw_layout(centre,localNormal,_flw_aMidTexCoord);
                    _flw_transform(value);
                    vec3 transformedCentre=flw_vertexPos.xyz;
                    guestMidUv=flw_vertexTexCoord;
                    vec3 localBinormal=cross(_flw_aTangent.xyz,localNormal)*_flw_aTangent.w;
                    _flw_layout(centre+_flw_aTangent.xyz*(1.0/16.0),localBinormal,_flw_aMidTexCoord);
                    _flw_transform(value);
                    vec3 transformedTangent=flw_vertexPos.xyz-transformedCentre;
                    vec3 transformedBinormalNormal=flw_vertexNormal;
                """);
        source.append("""
                    _flw_layout(_flw_aPos,localNormal,_flw_aTexCoord);
                    _flw_transform(value);
                    flw_vertexNormal=normalize(flw_vertexNormal);
                    // 1.12.2: instance callbacks use texel-centred light; native pack inputs and contract lighting use raw levels.
                    flw_vertexLight-=vec2(1.0/32.0);
                """);
        if (vertexLight) source.append("_flw_guestVertexLight();\n");
        if (proxy) {
            // Position deltas transform tangents correctly under nonuniform scale; normals use the inverse transpose.
            source.append("""
                        if (dot(transformedTangent,transformedTangent)>1e-12) {
                            float handedness=dot(cross(transformedTangent,flw_vertexNormal),transformedBinormalNormal)<0.0?-1.0:1.0;
                            guestTangent=vec4(normalize(transformedTangent),handedness);
                        }
                        if (_flw_aFaceNormal.w>0.5) guestMidBlock=vec4((transformedCentre-flw_vertexPos.xyz)*64.0,_flw_aMidBlock.w);
                    """);
        }
        source.append("""
                    if (dot(guestTangent.xyz,guestTangent.xyz)==0.0) {
                        vec3 axis=abs(flw_vertexNormal.y)<0.999?vec3(0.0,1.0,0.0):vec3(1.0,0.0,0.0);
                        guestTangent=vec4(normalize(cross(axis,flw_vertexNormal)),1.0);
                    }
                    _flw_vertexPosOriginRel=flw_vertexPos;
                    _flw_packVertex=flw_vertexPos+vec4(_flw_originRelToCameraRel,0.0);
                    flw_texCoord0=vec4(flw_vertexTexCoord,0.0,1.0);
                    flw_texCoord1=vec4(flw_vertexLight*256.0,0.0,1.0);
                    flw_texCoord2=vec4(vec2(flw_vertexOverlay),0.0,1.0);
                    flw_texCoord3=vec4(0.0,0.0,0.0,1.0);
                    flw_texCoord4=flw_texCoord3;
                    flw_texCoord5=flw_texCoord3;
                    flw_texCoord6=flw_texCoord3;
                    flw_texCoord7=flw_texCoord3;
                """);
        source.append("""
                ivec4 materialIds=_flw_aIrisEntity;
                if (_flw_overrideMaterial.x>=0) {
                    materialIds.xyz=_flw_overrideMaterial.xyz;
                    guestMidBlock.w=float(_flw_overrideMaterial.w);
                }
                """);
        input(source, inputs, "mc_Entity", "vec4(materialIds)");
        if (inputs.containsKey("iris_Entity")) input(source, inputs, "iris_Entity", "vec4(materialIds)");
        input(source, inputs, "at_tangent", "guestTangent*" + GuestRendering.tangentScale());
        input(source, inputs, "mc_midTexCoord", "vec4(guestMidUv,0.0,1.0)");
        input(source, inputs, "at_midBlock", "guestMidBlock");
        if (contractVaryings != null) source.append("clrwl_vertexTangent=guestTangent;\n");
        if (crumbling) source.append("""
                    vec2 faceUv=_flw_crumblingUv(_flw_vertexPosOriginRel.xyz,flw_vertexNormal);
                    flw_texCoord0=vec4(faceUv,0.0,1.0);
                """);
        // The pack owns its final position, including temporal jitter and shadow distortion.
        var result = source.append("_flw_shader_main();\n}\n").toString();
        return ShaderCompatibility.normalize(result, GL20.GL_VERTEX_SHADER);
    }

    public static void input(StringBuilder source, Map<String, String> inputs, String name, String value) {
        var type = inputs.getOrDefault(name, "vec4");
        var suffix = switch (type) {
            case "float", "int", "uint" -> ".x";
            case "vec2", "ivec2", "uvec2" -> ".xy";
            case "vec3", "ivec3", "uvec3" -> ".xyz";
            case "vec4", "ivec4", "uvec4" -> "";
            default -> throw new IllegalStateException("Unsupported shader input type " + type);
        };
        source.append(name).append('=').append(type).append('(').append(value).append(suffix).append(");\n");
    }
}
