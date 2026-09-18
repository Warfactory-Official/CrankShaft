taskNV in _FlwTerrainTask { _FLW_TASK_FIELDS } payload;

uint _flw_geometryBase;
uint _flw_geometryPrimitiveBase;
uint _flw_geometryEmitted;
uint _flw_geometryStripStart;
uint _flw_geometryTriangles;

void _flw_emitGeometry() {
    uint vertex = _flw_geometryBase + _flw_geometryEmitted++;
    gl_MeshVerticesNV[vertex].gl_Position = _flw_geometryPosition;
    _flw_storeVertex(vertex);
    uint stripSize = _flw_geometryEmitted - _flw_geometryStripStart;
    if (stripSize < 3u) return;
    uint primitive = _flw_geometryPrimitiveBase + _flw_geometryTriangles++;
    bool odd = (stripSize & 1u) == 0u;
    gl_PrimitiveIndicesNV[primitive * 3u] = vertex - (odd ? 1u : 2u);
    gl_PrimitiveIndicesNV[primitive * 3u + 1u] = vertex - (odd ? 2u : 1u);
    gl_PrimitiveIndicesNV[primitive * 3u + 2u] = vertex;
    gl_MeshPrimitivesNV[primitive].gl_PrimitiveID = _flw_geometryPrimitiveOut;
    gl_MeshPrimitivesNV[primitive].gl_Layer = _flw_geometryLayer;
    gl_MeshPrimitivesNV[primitive].gl_ViewportIndex = _flw_geometryViewport;
}

void _flw_endGeometry() {
    _flw_geometryStripStart = _flw_geometryEmitted;
}

void main() {
    uint lane = gl_LocalInvocationID.x;
    uint firstQuad = payload.firstQuad + gl_WorkGroupID.x * _FLW_MESH_QUADS;
    uint quads = min(uint(_FLW_MESH_QUADS), payload.quadCount - firstQuad);
    if (lane < quads * 4u) {
        _flw_regionSlot = payload.regionSlot;
        _flw_sourceBaseVertex = payload.sourceBaseVertex;
        _flw_geometry = payload.geometry;
        _flw_loadVertex(payload.baseVertex + firstQuad * 4u + lane);
        _flw_vertexMain();
        _flw_storeGeometryInput(lane);
    }
    barrier();
    uint maxTriangles = uint(_FLW_GS_MAX_VERTICES - 2);
    if (lane == 0u) gl_PrimitiveCountNV = quads * 2u * maxTriangles;
    if (lane < quads * 2u) {
        _flw_geometryBase = lane * _FLW_GS_MAX_VERTICES;
        _flw_geometryPrimitiveBase = lane * maxTriangles;
        _flw_geometryEmitted = 0u;
        _flw_geometryStripStart = 0u;
        _flw_geometryTriangles = 0u;
        // Unused output capacity is degenerate; preserve strip boundaries and provoking vertices without atomics.
        gl_MeshVerticesNV[_flw_geometryBase].gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
        for (uint triangle = 0u; triangle < maxTriangles; ++triangle) {
            uint primitive = _flw_geometryPrimitiveBase + triangle;
            uint index = primitive * 3u;
            gl_PrimitiveIndicesNV[index] = _flw_geometryBase;
            gl_PrimitiveIndicesNV[index + 1u] = _flw_geometryBase;
            gl_PrimitiveIndicesNV[index + 2u] = _flw_geometryBase;
            gl_MeshPrimitivesNV[primitive].gl_PrimitiveID = 0;
            gl_MeshPrimitivesNV[primitive].gl_Layer = 0;
            gl_MeshPrimitivesNV[primitive].gl_ViewportIndex = 0;
        }
        uint quadBase = (lane >> 1u) * 4u;
        uvec3 indices = (lane & 1u) == 0u ? uvec3(0, 1, 2) : uvec3(2, 3, 0);
        _flw_geometryPrimitiveIn = int(firstQuad * 2u + lane);
        _flw_geometryPrimitiveOut = _flw_geometryPrimitiveIn;
        _flw_geometryInvocation = 0;
        _flw_geometryLayer = 0;
        _flw_geometryViewport = 0;
        _flw_loadGeometryInput(quadBase + indices);
        _flw_gs_main();
    }
}
