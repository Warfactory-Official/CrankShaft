// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#include "flywheel:internal/terrain_region_input.glsl"

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
    return _flw_unpackRegionOrigin(regionInputEntry);
}

ivec3 sectionChunkOrigin(uvec4 regionInputEntry, uint sectionId) {
    return unpackRegionChunkOrigin(regionInputEntry) + unpackSectionOffset(sectionId);
}

#endif
