#include "flywheel:internal/indirect/model_descriptor.glsl"
#include "flywheel:internal/uniforms/uniforms.glsl"
#include "flywheel:internal/far/far.glsl"
#include "flywheel:internal/far/submission.glsl"
#include "flywheel:internal/far/frustum.glsl"
#include "flywheel:internal/far/occlusion.glsl"

layout(local_size_x = 32) in;

// 4 uints/page: model index, valid bits, baseUint (first instance), type id | stride uints << 16.
layout(std430, binding = 0) restrict readonly buffer PageBuffer {
    uint _flw_farPages[];
};

layout(std430, binding = 2) restrict writeonly buffer TargetBuffer {
    uint _flw_instanceIndices[];
};

layout(std430, binding = 3) restrict buffer ModelBuffer {
    ModelDescriptor _flw_models[];
};

layout(std430, binding = 5) restrict readonly buffer EntryLevelBuffer {
    uint _flw_farEntryLevels[];
};

layout(std430, binding = 15) restrict readonly buffer PartBuffer {
    FarPart _flw_farParts[];
};

// Rows of entries drawn as meshes, posed this frame, inside the frustum and not occluded.
void main() {
    // This dispatch has consumed its arguments; an entry must select meshes again to launch it next frame.
    if (gl_GlobalInvocationID.x == 0u) {
        _flw_farMeshGroupsX = 0u;
    }
    uint page = gl_WorkGroupID.x << 2u;
    if (page >= uint(_flw_farPages.length())) {
        return;
    }
    uint modelIndex = _flw_farPages[page];
    uint valid = _flw_farPages[page + 1u];
    uint typeInfo = _flw_farPages[page + 3u];
    uint objectUint = _flw_farPages[page + 2u] + gl_LocalInvocationID.x * (typeInfo >> 16u);

    bool visible = false;
    if (((valid >> gl_LocalInvocationID.x) & 1u) != 0u) {
        FarPart part = _flw_farParts[_flw_farRowPart(objectUint)];
        if (_flw_farEntryLevels[part.info.x] == _FLW_FAR_LEVEL_MESH) {
            vec3 center;
            float radius;
            _flw_unpackBoundingSphere(_flw_models[modelIndex].boundingSphere, center, radius);
            _flw_transformBoundingSphereUber(typeInfo & 0xFFFFu, objectUint, center, radius);
            _flw_farFrameSphere(part, center, radius);
            if (part.pivot.w != 0.0) {
                center = part.pivot.xyz + _flw_farRotate(center - part.pivot.xyz, part.axis.xyz,
                        _flw_farAngle(part.pivot.w, part.origin.w));
            }
            center += vec3(part.origin.xyz - _flw_renderOrigin.xyz);
            visible = _flw_farTestSphere(center, radius) && !_flw_farSphereOccluded(center, radius);
        }
    }
    if (visible) {
        uint local = atomicAdd(_flw_models[modelIndex].instanceCount, 1u);
        _flw_instanceIndices[_flw_models[modelIndex].baseInstance + local] = objectUint;
    }
}
