// Locations: GuestShaders.ATTRIBUTES.
in vec3 _flw_aPosition;
in vec4 _flw_aColor;
in vec2 _flw_aTexCoord;
in uvec2 _flw_aLight;
in vec3 _flw_aNormal;
in ivec2 _flw_aIrisEntity;
in vec2 _flw_aMidTexCoord;
in vec4 _flw_aTangent;
in vec4 _flw_aMidBlock;

#define flw_view (iris_transforms.ModelViewMat)

#ifdef _FLW_GUEST_CONTRACT
// Colorwheel ClrwlVertexData + engine packed material/clip; guest_contract_header.frag and guest_contract_geom.glsl
// declare the same block.
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
    #ifdef _FLW_GUEST_OIT
    float _flw_oitViewZ;
    #endif
};
#else
vec4 flw_vertexPos;
vec3 flw_vertexNormal;
vec4 flw_vertexColor;
vec2 flw_vertexTexCoord;
ivec2 flw_vertexOverlay;
vec2 flw_vertexLight;

vec2 _flw_clipData;
#ifdef _FLW_GUEST_OIT
out float _flw_oitViewZ;
#endif
#endif

// Indirect raw-binds it per batch (IndirectDrawManager.UBO_INSTANCE_DRAW); instancing binds it through the pass.
#ifdef _FLW_GUEST_INDIRECT
layout(std140, binding = 11) uniform _FlwInstanceDraw {
#else
layout(std140) uniform _FlwInstanceDraw {
#endif
    uvec2 _flw_drawPackedMaterial;
    float flw_systemSeconds;
    float flw_glintSpeedOption;
    float flw_glintStrengthOption;
    uint _flw_drawItemTag;
    float flw_partialTick;
};

#ifdef FLW_EMBEDDED
mat4 _flw_modelMatrix;
mat3 _flw_normalMatrix;
#endif

#ifdef _FLW_CRUMBLING
const int _FLW_FACE_DOWN = 0;
const int _FLW_FACE_UP = 1;
const int _FLW_FACE_NORTH = 2;
const int _FLW_FACE_SOUTH = 3;
const int _FLW_FACE_WEST = 4;
const int _FLW_FACE_EAST = 5;

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

// TaggedEnvironment tags: kind << 24 | (id + 1). Iris writes 0 into the other ids while one kind renders.
uint _flw_drawTag;
uint _flw_itemTag;

ivec4 _flw_guestIrisEntity() {
    ivec4 ids = ivec4(-1, -1, -1, 0);
    if (_flw_drawTag != 0u) {
        int id = int(_flw_drawTag & 0xFFFFFFu) - 1;
        ids = (_flw_drawTag >> 24u) == 2u ? ivec4(id, 0, 0, 0) : ivec4(0, id, 0, 0);
    }
    if (_flw_itemTag != 0u) {
        ids.z = int(_flw_itemTag & 0xFFFFFFu) - 1;
        if ((_flw_itemTag >> 24u) == 4u) {
            ids.y = 1;
        }
    }
    return ids;
}

// Extras after the instance/material/embed transform: proxy vertex at the block centre, tangent as normal, mid-UV as
// texcoord (Colorwheel). Non-block meshes: zero mid-block offset (Iris ENTITY) != Colorwheel mesh centre.
vec4 _flw_irisTangent = vec4(0.0);
vec2 _flw_irisMidTexCoord;
vec4 _flw_irisMidBlock = vec4(0.0);

vec4 _flw_guestTangent() {
    if (dot(_flw_irisTangent.xyz, _flw_irisTangent.xyz) > 0.0) {
        return _flw_irisTangent;
    }
    // Meshes without quads carry none; any unit vector orthogonal to the normal keeps a pack's TBN finite.
    vec3 axis = abs(flw_vertexNormal.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    return vec4(normalize(cross(axis, flw_vertexNormal)), 1.0);
}

vec4 _flw_proxyCentre;

void _flw_proxyLayout() {
    flw_vertexPos = vec4(_flw_aPosition + _flw_aMidBlock.xyz * (127.0 / 64.0), 1.0);
    flw_vertexColor = _flw_aColor;
    flw_vertexTexCoord = _flw_aMidTexCoord;
    flw_vertexOverlay = ivec2(0, 10);
    flw_vertexLight = (vec2(_flw_aLight) + 8.0) / 256.0;
    flw_vertexNormal = _flw_aTangent.xyz;
}

void _flw_proxyCapture() {
    _flw_proxyCentre = flw_vertexPos;
    _flw_irisTangent = dot(_flw_aTangent.xyz, _flw_aTangent.xyz) > 0.0
            ? vec4(normalize(flw_vertexNormal), _flw_aTangent.w) : vec4(0.0);
    _flw_irisMidTexCoord = flw_vertexTexCoord;
}

void _flw_proxyFinish() {
    _flw_irisMidBlock = vec4((_flw_proxyCentre.xyz - flw_vertexPos.xyz) * (64.0 / 127.0), _flw_aMidBlock.w);
}
