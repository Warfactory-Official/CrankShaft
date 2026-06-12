// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#ifndef MESHLET_GL_SECTION_DATA_GLSL
#define MESHLET_GL_SECTION_DATA_GLSL

#include "meshlet:terrain/gl/scene.glsl"
#include "meshlet:terrain/gl/scene_ubo.glsl"

struct SectionFields {
    uint baseElement;
    uint baseVertex;
    uint sliceMask;
    uint facingLo;
    uint facingHi;
    uint vertexCount[7];
};

SectionFields decodeSection(uint regionId, uint s) {
    uint base = regionId * (REGION_SIZE * 3u) + s * 3u;
    uvec4 b0 = u_sectionData0[base + 0u];
    uvec4 b1 = u_sectionData0[base + 1u];
    uvec4 b2 = u_sectionData0[base + 2u];
    SectionFields f;
    f.baseElement = b0.x;
    f.baseVertex = b0.y;
    f.facingLo = b0.z;
    f.facingHi = b0.w;
    f.sliceMask = b1.x;
    f.vertexCount[0] = b1.y;
    f.vertexCount[1] = b1.z;
    f.vertexCount[2] = b1.w;
    f.vertexCount[3] = b2.x;
    f.vertexCount[4] = b2.y;
    f.vertexCount[5] = b2.z;
    f.vertexCount[6] = b2.w;
    return f;
}

uint facingByte(SectionFields f, uint i) {
    return i < 4u ? (f.facingLo >> (i * 8u)) & 0xFFu
                  : (f.facingHi >> ((i - 4u) * 8u)) & 0xFFu;
}

uint cameraVisibleFacingMask(vec3 rel) {
    uint m = 1u << 6u;
    if (rel.x <   0.0) m |= 1u << 0u;
    if (rel.y <   0.0) m |= 1u << 1u;
    if (rel.z <   0.0) m |= 1u << 2u;
    if (rel.x > -16.0) m |= 1u << 3u;
    if (rel.y > -16.0) m |= 1u << 4u;
    if (rel.z > -16.0) m |= 1u << 5u;
    return m;
}

bool facingDrawn(SectionFields f, uint i, uint camMask) {
    uint slices = camMask & f.sliceMask;
    return f.vertexCount[i] != 0u && ((slices >> facingByte(f, i)) & 1u) == 1u;
}

uint sectionQuadCount(SectionFields f, uint camMask) {
    uint quads = 0u;
    for (uint i = 0u; i < FACING_COUNT; i++) {
        if (facingDrawn(f, i, camMask)) {
            quads += f.vertexCount[i] >> 2u;
        }
    }
    return quads;
}

#endif
