#ifndef _FLW_MESH_DIRECT
taskNV in _FlwTerrainTask { _FLW_TASK_FIELDS } payload;
#endif

void main() {
    uint lane = gl_LocalInvocationID.x;
#ifdef _FLW_MESH_DIRECT
    uint command = uint(_flw_commandBase + gl_DrawIDARB) * 5u;
    uint count = _flw_commands[command + 1u];
    uint firstQuad = (gl_WorkGroupID.x - count) * _FLW_MESH_QUADS;
    uint quads = min(uint(_FLW_MESH_QUADS), count - firstQuad);
    _flw_regionSlot = _flw_commands[command + 4u];
    _flw_sourceBaseVertex = _flw_commands[command + 3u];
    _flw_geometry = _flw_geometryPointers[_flw_regionSlot].geometry;
    uint baseVertex = _flw_sourceBaseVertex + (_flw_commands[command + 2u] / 6u) * 4u;
#else
    uint group = gl_WorkGroupID.x;
#ifdef _FLW_TASK_CULL
    group = payload.meshlets[group];
#endif
    uint firstQuad = payload.firstQuad + group * _FLW_MESH_QUADS;
    uint quads = min(uint(_FLW_MESH_QUADS), payload.quadCount - firstQuad);
#endif
    uint quad = lane >> 2u;
    bool visible = quad < quads;
#ifdef _FLW_TASK_CULL
    visible = visible && (payload.visibleQuads[group] & (1u << quad)) != 0u;
#endif
    if (visible) {
#ifndef _FLW_MESH_DIRECT
        _flw_regionSlot = payload.regionSlot;
        _flw_sourceBaseVertex = payload.sourceBaseVertex;
        _flw_geometry = payload.geometry;
        uint baseVertex = payload.baseVertex;
#endif
        uint vertex = baseVertex + (firstQuad + quad) * 4u + (lane & 3u);
        _flw_loadVertex(vertex);
#ifdef _FLW_QUAD_CONSTANTS
        if ((lane & 3u) == 0u) {
            // Only proven quad outputs are observable in this invocation of the pack program.
            _flw_vertexMain();
            _flw_storeQuad(quad);
            _flw_loadVertex(vertex);
        }
#endif
        _flw_vertexMain();
#ifdef _FLW_TASK_CULL
        _flw_vertexPosition = payload.positions[group * _FLW_MESH_QUADS * 4u + lane];
#endif
        gl_MeshVerticesNV[lane].gl_Position = _flw_vertexPosition;
        _flw_storeVertex(lane);
    } else if (quad < quads) {
        gl_MeshVerticesNV[lane].gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
    }
    if (lane == 0u) gl_PrimitiveCountNV = quads * 2u;
    if (lane < quads * 2u) {
        uint base = (lane >> 1u) * 4u;
        uvec3 indices = (lane & 1u) == 0u ? uvec3(0, 1, 2) : uvec3(2, 3, 0);
#ifdef _FLW_TASK_CULL
        if ((payload.visibleQuads[group] & (1u << (lane >> 1u))) == 0u) indices = uvec3(0);
#endif
        gl_PrimitiveIndicesNV[lane * 3u] = base + indices.x;
        gl_PrimitiveIndicesNV[lane * 3u + 1u] = base + indices.y;
        gl_PrimitiveIndicesNV[lane * 3u + 2u] = base + indices.z;
        gl_MeshPrimitivesNV[lane].gl_PrimitiveID = int(firstQuad * 2u + lane);
    }
}
