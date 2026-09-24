#include "flywheel:internal/material.glsl"
#include "flywheel:internal/packed_material.glsl"

struct FlwLightAo {
    vec2 light;
    float ao;
};

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
    #ifdef _FLW_GUEST_OIT
    float _flw_oitViewZ;
    #endif
};

uniform sampler2D Sampler1;

layout(std140) uniform Lighting {
    vec3 Light0_Direction;
    vec3 Light1_Direction;
};

#include "flywheel:internal/render_origin.glsl"

vec4 flw_sampleColor;
vec4 flw_fragColor;
ivec2 flw_fragOverlay;
vec2 flw_fragLight;
FlwMaterial flw_material;
