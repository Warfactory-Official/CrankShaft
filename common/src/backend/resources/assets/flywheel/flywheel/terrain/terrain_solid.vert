// _FLW_VK_BDA: bindless arena by device address; extensions declared in Java (VkPrograms).

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:chunksection.glsl>
#moj_import <minecraft:projection.glsl>

#ifdef _FLW_VK_BDA
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

#ifdef _FLW_VK
struct _FlwDrawData {
    ivec3 regionChunkOrigin;
    uint visBase;   // regionId * 256: this region's base into _flw_sectionFadeVis (bound whole, not sliced)
    uvec2 geoAddr;  // this region's Sodium arena device address, deref'd via _FlwGeoRef
    uvec2 _ddPad;
};
layout(std430, binding = 2) restrict readonly buffer _flw_DrawDataBuf {
    _FlwDrawData _flw_drawData[];
};
#else
layout(std140, binding = 10) uniform u_RegionChunkOrigin {
    ivec3 _flw_regionChunkOrigin;
    int _flw_regionPadding;
};
#endif

#ifdef _FLW_VK
layout(std430, binding = 1) restrict readonly buffer _flw_SectionFadeVis {
#else
layout(std430, binding = 11) restrict readonly buffer _flw_SectionFadeVis {
#endif
    float _flw_sectionFadeVis[];
};

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out float flw_chunkVisibility;

const uint POSITION_BITS = 20u;
const uint POSITION_MAX_COORD = 1u << POSITION_BITS; // 0x100000
const uint TEXTURE_BITS = 15u;
const uint TEXTURE_MAX_COORD = 1u << TEXTURE_BITS; // 0x8000
const uint TEXTURE_MAX_VALUE = TEXTURE_MAX_COORD - 1u; // 0x7FFF
const float VERTEX_SCALE = 32.0 / float(POSITION_MAX_COORD);
const float VERTEX_OFFSET = -8.0;

const int SECTION_X_OFFSET = 5;
const int SECTION_X_MASK = 0x7 << SECTION_X_OFFSET;
const int SECTION_Y_OFFSET = 0;
const int SECTION_Y_MASK = 0x3 << SECTION_Y_OFFSET;
const int SECTION_Z_OFFSET = 2;
const int SECTION_Z_MASK = 0x7 << SECTION_Z_OFFSET;

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
#ifdef _FLW_VK
    // gl_InstanceIndex = the command's baseInstance = its global stream index (instanceCount is always 1).
    _FlwDrawData _flw_dd = _flw_drawData[gl_InstanceIndex];
#endif
#ifdef _FLW_VK_BDA
    _FlwVertex _flw_v = _FlwGeoRef(_flw_dd.geoAddr).verts[gl_VertexIndex];
    uvec2 a_Position = uvec2(_flw_v.posHi, _flw_v.posLo);
    vec4 a_Color = unpackUnorm4x8(_flw_v.color);
    uvec2 a_TexCoord = uvec2(_flw_v.uv, _flw_v.uv >> 16u);
    uvec4 a_LightAndData = uvec4(_flw_v.light, _flw_v.light >> 8u, _flw_v.light >> 16u, _flw_v.light >> 24u) & 0xFFu;
#endif
    // precise: forbid FMA/reassociation so the mesh tier reproduces this transform bit-for-bit.
    precise vec3 sectionRelativeBlocks = (_deinterleave_u20x3(a_Position) * VERTEX_SCALE) + VERTEX_OFFSET;

#ifdef _FLW_VK
    ivec3 chunkOrigin = _flw_dd.regionChunkOrigin + _unpackSectionOffset(a_LightAndData.w);
#else
    ivec3 chunkOrigin = _flw_regionChunkOrigin + _unpackSectionOffset(a_LightAndData.w);
#endif
    vec3 sectionOriginBlocks = vec3(chunkOrigin) * 16.0;

    precise vec3 pos = (sectionOriginBlocks + sectionRelativeBlocks) - CameraBlockPos + CameraOffset;
    precise vec4 flwClipPos = ProjMat * ModelViewMat * vec4(pos, 1.0);
    gl_Position = flwClipPos;

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

    // encodeLight's +8 IS the half-texel centering; decode /256.0, not sample_lightmap (double-centers).
    vec2 uv_lightmap = vec2(a_LightAndData.xy) / 256.0;
    vertexColor = a_Color * texture(Sampler2, uv_lightmap);

    texCoord0 = vec2(a_TexCoord & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD);

#ifdef _FLW_VK
    flw_chunkVisibility = _flw_sectionFadeVis[int(_flw_dd.visBase) + (int(a_LightAndData.w) & 0xFF)];
#else
    flw_chunkVisibility = _flw_sectionFadeVis[int(a_LightAndData.w) & 0xFF];
#endif
}
