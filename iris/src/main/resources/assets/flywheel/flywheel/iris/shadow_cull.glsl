#include "flywheel:internal/indirect/buffer_bindings.glsl"
#include "flywheel:internal/indirect/model_descriptor.glsl"
#include "flywheel:util/matrix.glsl"
#include "flywheel:internal/indirect/matrices.glsl"

layout(local_size_x = 32) in;

layout(std430, binding = _FLW_DRAW_INSTANCE_INDEX_BUFFER_BINDING) restrict writeonly buffer TargetBuffer {
    uint _flw_instanceIndices[];
};

layout(std430, binding = _FLW_PAGE_FRAME_DESCRIPTOR_BUFFER_BINDING) restrict readonly buffer PageFrameDescriptorBuffer {
    uint _flw_pageFrameDescriptors[];
};

layout(std430, binding = _FLW_MODEL_BUFFER_BINDING) restrict buffer ModelBuffer {
    ModelDescriptor _flw_models[];
};

layout(std430, binding = _FLW_MATRIX_BUFFER_BINDING) restrict readonly buffer MatrixBuffer {
    Matrices _flw_matrices[];
};

// Iris's shadow frustum for this frame, shadow-camera-relative (GuestShadowCull.upload).
layout(std140) uniform _FlwShadowCull {
    vec4 _flw_shadowPlanes[13];
    vec3 _flw_shadowOriginOffset;
    int _flw_shadowPlaneCount;
    float _flw_shadowDistance; // <= 0: no distance box
    float _flw_shadowSafeZone; // <= 0: no safe zone
};

bool _flw_sphereOutsideBox(vec3 center, float radius, float halfExtent) {
    return any(greaterThan(abs(center) - radius, vec3(halfExtent)));
}

bool _flw_shadowVisible(vec3 center, float radius) {
    if (_flw_shadowDistance > 0.0 && _flw_sphereOutsideBox(center, radius, _flw_shadowDistance)) {
        return false;
    }
    if (_flw_shadowSafeZone > 0.0 && !_flw_sphereOutsideBox(center, radius, _flw_shadowSafeZone)) {
        return true;
    }
    for (int i = 0; i < _flw_shadowPlaneCount; i++) {
        vec4 plane = _flw_shadowPlanes[i];
        if (dot(plane.xyz, center) + plane.w < -radius * length(plane.xyz)) {
            return false;
        }
    }
    return true;
}

void main() {
    uint pageIndex = gl_WorkGroupID.x << 2u;

    if (pageIndex >= _flw_pageFrameDescriptors.length()) {
        return;
    }

    uint modelIndex = _flw_pageFrameDescriptors[pageIndex];
    uint pageValidity = _flw_pageFrameDescriptors[pageIndex + 1];
    uint baseUint = _flw_pageFrameDescriptors[pageIndex + 2];
    uint typeInfo = _flw_pageFrameDescriptors[pageIndex + 3];

    if (((1u << gl_LocalInvocationID.x) & pageValidity) == 0u) {
        return;
    }

    uint objectUint = baseUint + gl_LocalInvocationID.x * (typeInfo >> 16u);

    vec3 center;
    float radius;
    _flw_unpackBoundingSphere(_flw_models[modelIndex].boundingSphere, center, radius);
    _flw_transformBoundingSphereUber(typeInfo & 0xFFFFu, objectUint, center, radius);

    uint matrixIndex = _flw_models[modelIndex].matrixIndex & 0x7FFFFFFFu;
    if (matrixIndex > 0u) {
        transformBoundingSphere(_flw_matrices[matrixIndex].pose, center, radius);
    }

    if (!_flw_shadowVisible(center + _flw_shadowOriginOffset, radius)) {
        return;
    }

    uint localIndex = atomicAdd(_flw_models[modelIndex].instanceCount, 1u);
    _flw_instanceIndices[_flw_models[modelIndex].baseInstance + localIndex] = objectUint;
}
