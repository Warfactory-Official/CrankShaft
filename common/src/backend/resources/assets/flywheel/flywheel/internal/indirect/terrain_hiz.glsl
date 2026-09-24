// SPDX-License-Identifier: MIT

#include "flywheel:internal/terrain_region_input.glsl"

#include "flywheel:internal/indirect/hiz_aabb.glsl"

// Terrain-owned UBO (8), self-contained: the terrain path bypasses EngineImpl.render; frame uniforms may be stale.
layout(std140, binding = 8) uniform TerrainHiZUniforms {
    mat4 viewProjection;        // matrices.projection() * matrices.modelView()
    vec4 cameraPosAndPad;       // .xyz = cameraWorldPos - cameraBlockPos (fractional, for inside-AABB test)
    ivec4 cameraBlockPosAndPad; // .xyz = floor(cameraWorldPos) (block coords); corners subtract this
};

layout(binding = 10) uniform sampler2D hizPyramid;

// Sodium LocalSectionIndex is XZY-packed: x=(s>>5)&7, y=s&3, z=(s>>2)&7.
ivec3 _flw_sectionOffset(uint s) {
    return ivec3((s >> 5u) & 0x7u, s & 0x3u, (s >> 2u) & 0x7u);
}

bool _flw_terrainHizVisible(vec3 mins, vec3 maxs) {
    vec2 viewSize = vec2(cameraPosAndPad.w, float(cameraBlockPosAndPad.w));
    return _flw_hizAabbVisible(mins, maxs, cameraPosAndPad.xyz, viewProjection, viewSize, hizPyramid);
}

// 8 x 4 x 8 sections.
bool _flw_terrainRegionVisible(uvec4 in4) {
    vec3 start = vec3(_flw_unpackRegionOrigin(in4) * 16 - cameraBlockPosAndPad.xyz) - _FLW_HIZ_ADD_BLOCKS;
    vec3 end = start + vec3(128.0, 64.0, 128.0) + (_FLW_HIZ_ADD_BLOCKS * 2.0);
    return _flw_terrainHizVisible(start, end);
}

bool _flw_terrainSectionVisible(uvec4 in4, uint s) {
    vec3 cornerBase = vec3((_flw_unpackRegionOrigin(in4) + _flw_sectionOffset(s)) * 16 - cameraBlockPosAndPad.xyz);
    return _flw_terrainHizVisible(cornerBase - _FLW_HIZ_ADD_BLOCKS, cornerBase + vec3(16.0) + _FLW_HIZ_ADD_BLOCKS);
}
