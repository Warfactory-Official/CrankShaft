// DrawArraysIndirectCommand at byte 0, DispatchIndirectCommand at byte 16, resident row-page groups at byte 28.
layout(std430, binding = 13) restrict buffer FarSubmissionBuffer {
    uint _flw_farBoxVertexCount;
    uint _flw_farBoxInstanceCount;
    uint _flw_farBoxFirstVertex;
    uint _flw_farBoxBaseInstance;
    uint _flw_farMeshGroupsX;
    uint _flw_farMeshGroupsY;
    uint _flw_farMeshGroupsZ;
    uint _flw_farMeshPageGroups;
};
