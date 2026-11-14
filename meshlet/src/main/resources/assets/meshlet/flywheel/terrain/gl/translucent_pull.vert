// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 movblock

layout(std140, binding = 8) uniform TerrainHiZUniforms {
    mat4 viewProjection;
    vec4 cameraPosAndPad;
    ivec4 cameraBlockPosAndPad;
};

#include "meshlet:terrain/gl/translucent_cache.glsl"

layout(std430, binding = 10) restrict readonly buffer CachedVerts {
    CachedVert cachedVerts[];
};
layout(std430, binding = 11) restrict readonly buffer QuadFade {
#ifdef _FLW_TERRAIN_RECEIVER_DATA
    uvec2 quadData[];
#else
    float quadFade[];
#endif
};

layout(location = 1) out PerVertex {
    vec4 color;
    vec4 misc;
    vec2 fog;
} v_out;

#ifdef _FLW_TERRAIN_OVERLAY
layout(location = 6) out vec3 _flw_terrainPosition;
layout(location = 7) flat out uint _flw_terrainReceiver;
#endif

void main() {
    uint vid = uint(gl_VertexID);
    uint quad = vid / 6u;
    uint rem = vid - quad * 6u;
    uint corner = rem < 3u ? rem : (rem == 3u ? 0u : rem - 2u);
    CachedVert v = cachedVerts[quad * 4u + corner];

    vec3 pos = vec3(v.px, v.py, v.pz);
    vec4 clip = viewProjection * vec4(pos, 1.0);
    gl_Position = clip;
    #ifdef _FLW_TERRAIN_OVERLAY
    _flw_terrainPosition = pos;
    _flw_terrainReceiver = quadData[quad].y;
    #endif

    v_out.color = vec4(unpackHalf2x16(v.colorRG), unpackHalf2x16(v.colorBA));
    vec2 uv = vec2(uvec2(v.uv, v.uv >> 16u) & 0x7FFFu) / 32768.0;
    #ifdef _FLW_TERRAIN_RECEIVER_DATA
    float fade = uintBitsToFloat(quadData[quad].x);
    #else
    float fade = quadFade[quad];
    #endif
    v_out.misc = vec4(uv, -clip.w, fade);
    v_out.fog = vec2(length(pos), max(length(pos.xz), abs(pos.y)));
}
