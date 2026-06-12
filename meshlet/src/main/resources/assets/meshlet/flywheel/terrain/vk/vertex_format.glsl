// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2025 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#ifndef MESHLET_VK_VERTEX_FORMAT_GLSL
#define MESHLET_VK_VERTEX_FORMAT_GLSL

struct Vertex {
    uint posHi;
    uint posLo;
    uint color;
    uint uv;
    uint light;
};

const uint POSITION_BITS = 20u;
const uint POSITION_MAX_COORD = 1u << POSITION_BITS;
const float VERTEX_SCALE = 32.0 / float(POSITION_MAX_COORD);
const float VERTEX_OFFSET = -8.0;

const uint TEXTURE_BITS = 15u;
const uint TEXTURE_MAX_COORD = 1u << TEXTURE_BITS;
const uint TEXTURE_MAX_VALUE = TEXTURE_MAX_COORD - 1u;

uvec3 _deinterleave_u20x3(uint hi, uint lo) {
    uvec3 h = (uvec3(hi) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    uvec3 l = (uvec3(lo) >> uvec3(0u, 10u, 20u)) & 0x3FFu;
    return (h << 10u) | l;
}

vec3 decodeVertexPosition(Vertex v) {
    return (vec3(_deinterleave_u20x3(v.posHi, v.posLo)) * VERTEX_SCALE) + VERTEX_OFFSET;
}

vec4 decodeVertexColour(Vertex v) {
    uvec3 c = (uvec3(v.color) >> uvec3(0u, 8u, 16u)) & 0xFFu;
    return vec4(vec3(c) / 255.0, 1.0);
}

vec2 decodeVertexUV(Vertex v) {
    return vec2(uvec2(v.uv, v.uv >> 16u) & TEXTURE_MAX_VALUE) / float(TEXTURE_MAX_COORD);
}

vec2 decodeLightUV(Vertex v) {
    return vec2(uvec2(v.light, v.light >> 8u) & 0xFFu) / 256.0;
}

uint decodeMaterial(Vertex v) {
    return (v.light >> 16u) & 0xFFu;
}

uint decodeSectionId(Vertex v) {
    return (v.light >> 24u) & 0xFFu;
}

uint decodeAlphaCutoffId(Vertex v) {
    return (decodeMaterial(v) >> 1u) & 3u;
}

#endif
