#include "flywheel:internal/terrain_region_input.glsl"

#ifndef _FLW_GUEST_MESH_SCENE
#define _FLW_GUEST_MESH_SCENE

struct _FlwGuestVertex { uint words[_FLW_VERTEX_WORDS]; };
#ifdef _FLW_CACHE_RECOVERY
#define _FLW_HISTORY_TYPE uvec4
#else
#define _FLW_HISTORY_TYPE uint
#endif
struct _FlwGuestArena { restrict _FlwGuestVertex * geometry; restrict _FLW_HISTORY_TYPE * visibility; };
struct _FlwGuestRun { uvec4 draw; uvec4 dispatch; };

layout(std140, binding = 7) uniform _FlwGuestMeshScene {
    restrict readonly uvec4 * _flw_regionInput;
    restrict uint * _flw_commands;
    restrict uvec2 * _flw_drawCounts;
    restrict readonly _FlwGuestArena * _flw_geometryPointers;
    restrict _FlwGuestRun * _flw_runs;
    uint _flw_taskCull;
    uint _flw_taskPhase;
    ivec2 _flw_viewSize;
    restrict uint * _flw_compactedCommands;
};

uniform int _flw_commandBase;

#ifdef _FLW_TASK_CULL
#define _FLW_TASK_CULL_FIELDS restrict _FLW_HISTORY_TYPE * visibility; uint visibleQuads[_FLW_TASK_QUADS / _FLW_MESH_QUADS]; uint meshlets[_FLW_TASK_QUADS / _FLW_MESH_QUADS]; vec4 positions[_FLW_TASK_QUADS * 4];
#else
#define _FLW_TASK_CULL_FIELDS
#endif

#define _FLW_TASK_FIELDS \
    uint regionSlot; \
    uint baseVertex; \
    uint sourceBaseVertex; \
    uint quadCount; \
    uint firstQuad; \
    restrict _FlwGuestVertex * geometry; \
    _FLW_TASK_CULL_FIELDS

#endif
