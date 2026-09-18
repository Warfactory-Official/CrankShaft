#include "meshlet:iris/terrain/scene.glsl"

#ifdef _FLW_TASK_CULL
layout(local_size_x = 32) in;
#else
layout(local_size_x = 1) in;
#endif
taskNV out _FlwTerrainTask { _FLW_TASK_FIELDS } payload;

#ifdef _FLW_TASK_CULL
#include "meshlet:iris/terrain/task_cached_cull.glsl"
#endif

void main() {
    uint command = uint(_flw_commandBase + gl_DrawIDARB) * 5u;
    uint quads = _flw_commands[command + 1u];
    uint slot = _flw_commands[command + 4u];
    if (gl_LocalInvocationID.x == 0u) {
        payload.regionSlot = slot;
        payload.sourceBaseVertex = _flw_commands[command + 3u];
        payload.baseVertex = payload.sourceBaseVertex + (_flw_commands[command + 2u] / 6u) * 4u;
        payload.quadCount = quads;
        // The NV first-task value preserves the quad count; no bounded bit packing of command IDs.
        payload.firstQuad = (gl_WorkGroupID.x - quads) * _FLW_TASK_QUADS;
        payload.geometry = _flw_geometryPointers[slot].geometry;
#ifdef _FLW_TASK_CULL
        payload.visibility = _flw_geometryPointers[slot].visibility;
#endif
    }
#ifdef _FLW_TASK_CULL
    barrier();
    uint lane = gl_LocalInvocationID.x;
    uint meshCount = 0u;
    for (uint group = 0u; group < uint(_FLW_TASK_QUADS / _FLW_MESH_QUADS); ++group) {
        uint firstQuad = payload.firstQuad + group * _FLW_MESH_QUADS;
        uint count = firstQuad < quads ? min(uint(_FLW_MESH_QUADS), quads - firstQuad) : 0u;
        uint firstVertex = payload.baseVertex + firstQuad * 4u;
        uint positionBase = group * _FLW_MESH_QUADS * 4u;
        bool evaluateVertex = lane < count * 4u;
#ifdef _FLW_TASK_DEPTH_SAFE
        if (evaluateVertex && _flw_taskPhase == 2u) {
            evaluateVertex = (payload.visibility[(firstVertex >> 2u) + (lane >> 2u)].x & 3u) == 3u;
        }
#endif
        if (evaluateVertex) {
            _flw_regionSlot = slot;
            _flw_sourceBaseVertex = payload.sourceBaseVertex;
            _flw_geometry = payload.geometry;
            _flw_loadVertex(firstVertex + lane);
            _flw_vertexMain();
            payload.positions[positionBase + lane] = _flw_vertexPosition;
        }
        barrier();
        uvec4 test = uvec4(2, 0, 0, 0);
#ifdef _FLW_TASK_DEPTH_SAFE
        if (lane < count && _flw_taskPhase == 2u) {
            // The recovery compute already tested current depth; only its accepted quads need pack vertex work.
            test.x = (payload.visibility[(firstVertex >> 2u) + lane].x & 3u) == 3u ? 1u : 2u;
        } else
#endif
        if (lane < count) {
            uint position = positionBase + lane * 4u;
            test = _flw_quadVisibility(payload.positions[position], payload.positions[position + 1u],
                    payload.positions[position + 2u], payload.positions[position + 3u]);
            // One record per arena quad; phase one rewrites every candidate before same-frame recovery.
#ifdef _FLW_TASK_DEPTH_SAFE
            if (_flw_taskPhase == 1u) {
                if ((test.x & 3u) == 0u) payload.visibility[(firstVertex >> 2u) + lane] = test;
                else payload.visibility[(firstVertex >> 2u) + lane].x = test.x;
            }
#endif
        }
        uint visibleQuads = subgroupOr((test.x & 3u) == 1u ? 1u << lane : 0u);
        if (lane == 0u) {
            payload.visibleQuads[group] = visibleQuads;
            if (visibleQuads != 0u) payload.meshlets[meshCount++] = group;
        }
    }
    if (lane == 0u) gl_TaskCountNV = meshCount;
#else
    uint count = min(uint(_FLW_TASK_QUADS), quads - payload.firstQuad);
    gl_TaskCountNV = _flw_taskPhase == 2u ? 0u : (count + _FLW_MESH_QUADS - 1u) / _FLW_MESH_QUADS;
#endif
}
