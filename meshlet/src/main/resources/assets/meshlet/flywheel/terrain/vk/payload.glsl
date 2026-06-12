// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2023-2026 Cortex and Nvidium contributors
// Copyright (C) 2026 movblock

#ifndef MESHLET_VK_TASK_PAYLOAD_GLSL
#define MESHLET_VK_TASK_PAYLOAD_GLSL

#define TASK_FACING_COUNT 7

struct TerrainTaskPayload {
    uint64_t geoAddr;
    uint baseVertex;
    uint quadCount;
    uint sectionId;
    float chunkVisibility;
    uint slot;
    uint facingArenaQuadStart[TASK_FACING_COUNT];
    uint facingVisibleQuadStart[TASK_FACING_COUNT];
};

taskPayloadSharedEXT TerrainTaskPayload payload;

#endif
