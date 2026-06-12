// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#ifndef MESHLET_VK_TRANSLUCENT_SECTION_DATA_GLSL
#define MESHLET_VK_TRANSLUCENT_SECTION_DATA_GLSL


const uint REGION_SIZE = 256u;

layout(set = 0, binding = 0, std430) readonly buffer RegionInput {
    uvec4 regionInput[];
};

layout(set = 0, binding = 1, std430) readonly buffer SectionData {
    uvec4 sectionData[];
};

struct SectionFields {
    uint baseVertex;
    uint totalVerts;
};

SectionFields decodeSection(uint regionId, uint s) {
    uint base = regionId * (REGION_SIZE * 3u) + s * 3u;
    uvec4 b0 = sectionData[base + 0u];
    uvec4 b1 = sectionData[base + 1u];
    uvec4 b2 = sectionData[base + 2u];
    SectionFields f;
    f.baseVertex = b0.y;
    f.totalVerts = b1.y + b1.z + b1.w + b2.x + b2.y + b2.z + b2.w;
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

#endif
