// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#ifndef MESHLET_VK_SECTION_DATA_GLSL
#define MESHLET_VK_SECTION_DATA_GLSL


const uint FACING_COUNT = 7u;
const uint REGION_SIZE = 256u;
const uint SECTION_ID_REGION_SHIFT = 8u;
const uint SECTION_ID_SLOT_MASK = 0xFFu;

layout(set = 0, binding = 0, std430) readonly buffer RegionInput {
    uvec4 regionInput[];
};

layout(set = 0, binding = 1, std430) readonly buffer SectionData {
    uvec4 sectionData[];
};

#include "meshlet:terrain/vk/hiz_uniforms.glsl"

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
    uvec4 b0 = sectionData[base + 0u];
    uvec4 b1 = sectionData[base + 1u];
    uvec4 b2 = sectionData[base + 2u];
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

ivec3 unpackSectionOffset(uint sectionId) {
    int idx = int(sectionId);
    return ivec3((idx >> 5) & 0x7, (idx >> 0) & 0x3, (idx >> 2) & 0x7);
}

ivec3 unpackRegionChunkOrigin(uvec4 regionInputEntry) {
    int packedXZ = int(regionInputEntry.x);
    int cx = (packedXZ << 16) >> 16;
    int cz = packedXZ >> 16;
    int cy = (int(regionInputEntry.y) << 16) >> 16;
    return ivec3(cx, cy, cz);
}

ivec3 sectionChunkOrigin(uvec4 regionInputEntry, uint sectionId) {
    return unpackRegionChunkOrigin(regionInputEntry) + unpackSectionOffset(sectionId);
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
