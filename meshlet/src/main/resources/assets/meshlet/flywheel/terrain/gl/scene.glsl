// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#ifndef MESHLET_GL_SCENE_GLSL
#define MESHLET_GL_SCENE_GLSL

const uint FACING_COUNT = 7u;
const uint REGION_SIZE = 256u;
const uint SECTION_ID_REGION_SHIFT = 8u;
const uint SECTION_ID_SLOT_MASK = 0xFFu;

layout(std140, binding = 8) uniform TerrainHiZUniforms {
    mat4 viewProjection;
    vec4 cameraPosAndPad;
    ivec4 cameraBlockPosAndPad;
};

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
