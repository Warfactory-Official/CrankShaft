#include "flywheel:internal/indirect/model_descriptor.glsl"
#include "flywheel:internal/uniforms/uniforms.glsl"
#include "flywheel:internal/far/far.glsl"
#include "flywheel:internal/far/submission.glsl"
#include "flywheel:internal/far/frustum.glsl"
#include "flywheel:internal/far/occlusion.glsl"

layout(local_size_x = 64) in;

layout(std430, binding = 3) restrict buffer ModelBuffer {
    ModelDescriptor _flw_models[];
};

layout(std430, binding = 5) restrict writeonly buffer EntryLevelBuffer {
    uint _flw_farEntryLevels[];
};

layout(std430, binding = 9) restrict readonly buffer EntryBuffer {
    FarEntry _flw_farEntries[];
};

void _flw_farSelectMesh(uint slot) {
    _flw_farEntryLevels[slot] = _FLW_FAR_LEVEL_MESH;
    atomicExchange(_flw_farMeshGroupsX, _flw_farMeshPageGroups);
}

// Per entry slot: culled (dead, outside the frustum, under half a pixel or occluded), mesh (a block at the bounds' nearest point
// projects to at least the entry's switch, a part is not baked yet or the camera is inside) or impostor.
void main() {
    uint slot = gl_GlobalInvocationID.x;
    if (slot == 0u) {
        _flw_farBoxVertexCount = 36u;
        _flw_farBoxInstanceCount = 0u;
        _flw_farBoxFirstVertex = 0u;
        _flw_farBoxBaseInstance = 0u;
    }
    // Model metadata stays resident; reset only the counters that the row cull fills this frame.
    if (slot < uint(_flw_models.length())) {
        _flw_models[slot].instanceCount = 0u;
    }
    if (slot >= _flw_farCount) {
        return;
    }
    FarEntry entry = _flw_farEntries[slot];
    if (entry.origin.w == 0) {
        _flw_farEntryLevels[slot] = _FLW_FAR_LEVEL_CULLED;
        return;
    }
    // Authored instance shaders keep running. Their animated bounds are evaluated by the row cull this frame.
    if ((uint(entry.origin.w) & _FLW_FAR_ENTRY_MESH_ONLY) != 0u) {
        _flw_farSelectMesh(slot);
        return;
    }
    vec3 lo = vec3(_flw_farUnsortable(entry.boundsMin.x), _flw_farUnsortable(entry.boundsMin.y),
            _flw_farUnsortable(entry.boundsMin.z));
    vec3 hi = vec3(_flw_farUnsortable(entry.boundsMax.x), _flw_farUnsortable(entry.boundsMax.y),
            _flw_farUnsortable(entry.boundsMax.z));
    // Bounds land the frame after the capture; its rows cull themselves meanwhile.
    if (any(greaterThan(lo, hi))) {
        _flw_farSelectMesh(slot);
        return;
    }
    vec3 offset = vec3(entry.origin.xyz - _flw_renderOrigin.xyz);
    float radius = length(hi - lo) * 0.5;
    if (!_flw_farTestSphere((lo + hi) * 0.5 + offset, radius)) {
        _flw_farEntryLevels[slot] = _FLW_FAR_LEVEL_CULLED;
        return;
    }
    vec3 eye = _flw_cameraPos.xyz - offset;
    float pixelsPerBlock = _flw_cullData.P11 * _flw_cullData.viewHeight * 0.5
            / max(distance(clamp(eye, lo, hi), eye), 1.0);
    if (pixelsPerBlock * radius < 0.5) {
        _flw_farEntryLevels[slot] = _FLW_FAR_LEVEL_CULLED;
        return;
    }
    if (_flw_farOccluded(lo + offset, hi + offset)) {
        _flw_farEntryLevels[slot] = _FLW_FAR_LEVEL_CULLED;
        return;
    }
    float meshSwitch = uintBitsToFloat(entry.boundsMin.w) * _flw_farMeshScale;
    bool inside = all(greaterThan(eye, lo)) && all(lessThan(eye, hi));
    if (pixelsPerBlock >= meshSwitch || entry.boundsMax.w == 0u || inside) {
        _flw_farSelectMesh(slot);
    } else {
        _flw_farEntryLevels[slot] = _FLW_FAR_LEVEL_IMPOSTOR;
    }
}
