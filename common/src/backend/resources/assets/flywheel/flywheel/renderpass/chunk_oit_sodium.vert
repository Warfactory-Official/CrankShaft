// _FLW_TRANSLUCENT_INSTANCED: GPU-driven bindless replay; extensions declared in Java (VkPrograms).

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:projection.glsl>

// Sodium CompactChunkVertex: pos uvec2 (20-bit), color RGBA8 (AO pre-baked), uv RG16,
// light RGBA8_UINT (w = section id).
#ifdef _FLW_TRANSLUCENT_INSTANCED
struct _FlwVertex {
    uint posHi;
    uint posLo;
    uint color;
    uint uv;
    uint light;
};
layout(buffer_reference, std430, buffer_reference_align = 4) restrict readonly buffer _FlwGeoRef {
    _FlwVertex verts[];
};
#else
layout(location = 0) in uvec2 a_Position;
layout(location = 1) in vec4 a_Color;
layout(location = 2) in uvec2 a_TexCoord;
layout(location = 3) in uvec4 a_LightAndData;
#endif

uniform sampler2D Sampler2; // lightmap

#ifdef _FLW_TRANSLUCENT_INSTANCED
// GPU-driven bindless: one 8-uint record per VISIBLE section ([0..2] origin, [3] fade, [4..5] arena addr).
layout(std430, binding = 1) restrict readonly buffer _flw_TranslucentDrawBuf {
    uint _flw_translucentDraw[];
};
#elif defined(_FLW_VK)
layout(std140, binding = 23) uniform u_RegionChunkOrigin {
    ivec3 _flw_regionChunkOrigin;
    int _flw_regionPadding;
};
#else
layout(std140, binding = 10) uniform u_RegionChunkOrigin {
    ivec3 _flw_regionChunkOrigin;
    int _flw_regionPadding;
};
#endif

#ifdef _FLW_TRANSLUCENT_FADE
layout(std430, binding = 11) restrict readonly buffer _flw_TranslucentVisBuf {
    float _flw_translucentVis[];
};
#endif

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out float flw_oitViewZ;
out float flw_chunkVisibility;

const uint POSITION_BITS = 20u;
const uint POSITION_MAX_COORD = 1u << POSITION_BITS; // 0x100000
const uint TEXTURE_BITS = 15u;
const uint TEXTURE_MAX_COORD = 1u << TEXTURE_BITS; // 0x8000
const uint TEXTURE_MAX_VALUE = TEXTURE_MAX_COORD - 1u; // 0x7FFF
const float VERTEX_SCALE = 32.0 / float(POSITION_MAX_COORD);
const float VERTEX_OFFSET = -8.0;

const int SECTION_X_OFFSET = 5;
const int SECTION_Y_OFFSET = 0;
const int SECTION_Z_OFFSET = 2;

uvec3 _deinterleave_u20x3(uvec2 data) {
    uvec3 hi = (uvec3(data.x) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    uvec3 lo = (uvec3(data.y) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    return (hi << 10u) | lo;
}

ivec3 _unpackSectionOffset(uint sectionId) {
    int idx = int(sectionId);
    return ivec3(
        (idx >> SECTION_X_OFFSET) & 0x7,
        (idx >> SECTION_Y_OFFSET) & 0x3,
        (idx >> SECTION_Z_OFFSET) & 0x7);
}

void main() {
#ifdef _FLW_TRANSLUCENT_INSTANCED
    // Bindless: draw record carries origin/fade/arena addr; gl_VertexIndex includes Sodium baseVertex.
    uint _flw_db = uint(gl_InstanceIndex) * 8u;
    ivec3 regionChunkOrigin = ivec3(int(_flw_translucentDraw[_flw_db]), int(_flw_translucentDraw[_flw_db + 1u]), int(_flw_translucentDraw[_flw_db + 2u]));
    uvec2 _flw_geoAddr = uvec2(_flw_translucentDraw[_flw_db + 4u], _flw_translucentDraw[_flw_db + 5u]);
    _FlwVertex _flw_v = _FlwGeoRef(_flw_geoAddr).verts[gl_VertexIndex];
    uvec2 a_Position = uvec2(_flw_v.posHi, _flw_v.posLo);
    vec4 a_Color = unpackUnorm4x8(_flw_v.color);
    uvec2 a_TexCoord = uvec2(_flw_v.uv, _flw_v.uv >> 16u);
    uvec4 a_LightAndData = uvec4(_flw_v.light, _flw_v.light >> 8u, _flw_v.light >> 16u, _flw_v.light >> 24u) & 0xFFu;
#else
    ivec3 regionChunkOrigin = _flw_regionChunkOrigin;
#endif

    vec3 sectionRelativeBlocks = (_deinterleave_u20x3(a_Position) * VERTEX_SCALE) + VERTEX_OFFSET;

    ivec3 chunkOrigin = regionChunkOrigin + _unpackSectionOffset(a_LightAndData.w);
    vec3 sectionOriginBlocks = vec3(chunkOrigin) * 16.0;

    vec3 pos = (sectionOriginBlocks + sectionRelativeBlocks) - CameraBlockPos + CameraOffset;
    vec4 viewPos = ModelViewMat * vec4(pos, 1.0);
    gl_Position = ProjMat * viewPos;
    flw_oitViewZ = viewPos.z;

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

    vec2 uv_lightmap = vec2(a_LightAndData.xy) / 256.0;
    vertexColor = a_Color * texture(Sampler2, uv_lightmap);

    texCoord0 = vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD);

#if defined(_FLW_TRANSLUCENT_FADE)
    flw_chunkVisibility = _flw_translucentVis[int(a_LightAndData.w) & 0xFF];
#elif defined(_FLW_TRANSLUCENT_MDI)
    flw_chunkVisibility = 1.0;
#elif defined(_FLW_TRANSLUCENT_INSTANCED)
    flw_chunkVisibility = uintBitsToFloat(_flw_translucentDraw[_flw_db + 3u]);
#else
    flw_chunkVisibility = ChunkVisibility;
#endif
}
