#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:light.glsl>

#define flw_view ModelViewMat

// Names + order match InternalVertex.VERTEX_FORMAT; UV1 is in the format for byte-layout alignment but unused.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in uvec2 UV2; // light (RG16_UINT)
in vec3 Normal;

out vec4 flw_vertexPos;
out vec3 flw_vertexNormal;
vec4 flw_vertexColor;
vec2 flw_vertexTexCoord;
ivec2 flw_vertexOverlay;
vec2 flw_vertexLight;

out vec4 vertexColor;
out vec2 texCoord0;
out vec2 lightCoord;
flat out ivec2 overlayCoord;
out float sphericalVertexDistance;
out float cylindricalVertexDistance;

flat out uvec2 _flw_packedMaterial;

// GL: raw-bound at 11 per MultiDraw (trySetup hoist); instancing/crumbling use pass.setUniform. VK: set 0, bind 21.
layout(std140, binding = 11) uniform _FlwInstanceDraw {
    uvec2 _flw_drawPackedMaterial;
    float flw_systemSeconds;
    float flw_glintSpeedOption;
    float flw_glintStrengthOption;
};

out vec2 _flw_clipData;

out float flw_oitViewZ;

#ifdef FLW_EMBEDDED
mat4 _flw_modelMatrix;
mat3 _flw_normalMatrix;
#endif

// Crumbling: project onto the nearest axis face so cracks tile 1:1 with the block grid.
#ifdef _FLW_CRUMBLING
out vec2 _flw_crumblingTexCoord;

const int _FLW_FACE_DOWN = 0;
const int _FLW_FACE_UP = 1;
const int _FLW_FACE_NORTH = 2;
const int _FLW_FACE_SOUTH = 3;
const int _FLW_FACE_WEST = 4;
const int _FLW_FACE_EAST = 5;

// based on net.minecraftforge.client.ForgeHooksClient.getNearestStable (dot(normal, axis) == normal.axis)
int _flw_getNearestFacing(vec3 normal) {
    float maxAlignment = -2.0;
    int face = _FLW_FACE_NORTH;
    if (-normal.y > maxAlignment) { maxAlignment = -normal.y; face = _FLW_FACE_DOWN; }
    if (normal.y > maxAlignment)  { maxAlignment = normal.y;  face = _FLW_FACE_UP; }
    if (-normal.z > maxAlignment) { maxAlignment = -normal.z; face = _FLW_FACE_NORTH; }
    if (normal.z > maxAlignment)  { maxAlignment = normal.z;  face = _FLW_FACE_SOUTH; }
    if (-normal.x > maxAlignment) { maxAlignment = -normal.x; face = _FLW_FACE_WEST; }
    if (normal.x > maxAlignment)  { maxAlignment = normal.x;  face = _FLW_FACE_EAST; }
    return face;
}

vec2 _flw_getCrumblingTexCoord() {
    switch (_flw_getNearestFacing(flw_vertexNormal)) {
        case _FLW_FACE_DOWN:  return vec2(flw_vertexPos.x, -flw_vertexPos.z);
        case _FLW_FACE_UP:    return vec2(flw_vertexPos.x, flw_vertexPos.z);
        case _FLW_FACE_NORTH: return vec2(-flw_vertexPos.x, -flw_vertexPos.y);
        case _FLW_FACE_SOUTH: return vec2(flw_vertexPos.x, -flw_vertexPos.y);
        case _FLW_FACE_WEST:  return vec2(-flw_vertexPos.z, -flw_vertexPos.y);
        case _FLW_FACE_EAST:  return vec2(flw_vertexPos.z, -flw_vertexPos.y);
    }
    return vec2(-flw_vertexPos.x, -flw_vertexPos.y);
}
#endif
