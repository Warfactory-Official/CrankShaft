in ClrwlVertexData {
    vec4 flw_vertexPos;
    vec4 flw_vertexColor;
    vec2 flw_vertexTexCoord;
    flat ivec2 flw_vertexOverlay;
    vec2 flw_vertexLight;
    vec3 flw_vertexNormal;
    vec4 clrwl_vertexTangent;
    flat uvec2 _flw_packedMaterial;
    vec2 _flw_clipData;
} clrwl_in[3];

out ClrwlVertexData {
    vec4 flw_vertexPos;
    vec4 flw_vertexColor;
    vec2 flw_vertexTexCoord;
    flat ivec2 flw_vertexOverlay;
    vec2 flw_vertexLight;
    vec3 flw_vertexNormal;
    vec4 clrwl_vertexTangent;
    flat uvec2 _flw_packedMaterial;
    vec2 _flw_clipData;
} clrwl_out;

void clrwl_setVertexOut(int i) {
    clrwl_out.flw_vertexPos = clrwl_in[i].flw_vertexPos;
    clrwl_out.flw_vertexColor = clrwl_in[i].flw_vertexColor;
    clrwl_out.flw_vertexTexCoord = clrwl_in[i].flw_vertexTexCoord;
    clrwl_out.flw_vertexOverlay = clrwl_in[i].flw_vertexOverlay;
    clrwl_out.flw_vertexLight = clrwl_in[i].flw_vertexLight;
    clrwl_out.flw_vertexNormal = clrwl_in[i].flw_vertexNormal;
    clrwl_out.clrwl_vertexTangent = clrwl_in[i].clrwl_vertexTangent;
    clrwl_out._flw_packedMaterial = clrwl_in[i]._flw_packedMaterial;
    clrwl_out._flw_clipData = clrwl_in[i]._flw_clipData;
}
