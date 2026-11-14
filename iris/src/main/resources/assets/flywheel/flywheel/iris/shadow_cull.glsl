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

// Word 2 per page slot: an ordered model's visible bits for the ordered compaction.
layout(std430, binding = _FLW_VIS_WORDS_BUFFER_BINDING) restrict writeonly buffer _FlwVisWordsBuffer {
    uint _flw_visWords[];
};

shared uint _flw_visWord;

#include "flywheel:iris/shadow_frustum.glsl"

bool _flw_shadowVisible(uint objectUint, uint modelIndex, uint typeId) {
    vec3 center;
    float radius;
    _flw_unpackBoundingSphere(_flw_models[modelIndex].boundingSphere, center, radius);
    _flw_transformBoundingSphereUber(typeId, objectUint, center, radius);

    uint matrixIndex = _flw_models[modelIndex].matrixIndex & _FLW_MODEL_MATRIX_MASK;
    if (matrixIndex > 0u) {
        transformBoundingSphere(_flw_matrices[matrixIndex].pose, center, radius);
    }

    return _flw_shadowVisible(center + _flw_shadowOriginOffset, radius);
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

    // Uniform per workgroup; a free slot carries no valid bits.
    bool ordered = pageValidity != 0u
            && (_flw_models[modelIndex].matrixIndex & _FLW_MODEL_ORDERED) != 0u;
    if (ordered) {
        if (gl_LocalInvocationID.x == 0u) {
            _flw_visWord = 0u;
        }
        barrier();
    }

    uint objectUint = baseUint + gl_LocalInvocationID.x * (typeInfo >> 16u);
    bool visible = ((1u << gl_LocalInvocationID.x) & pageValidity) != 0u
            && _flw_shadowVisible(objectUint, modelIndex, typeInfo & 0xFFFFu);

    if (ordered) {
        if (visible) {
            atomicOr(_flw_visWord, 1u << gl_LocalInvocationID.x);
        }
        barrier();
        if (gl_LocalInvocationID.x == 0u) {
            _flw_visWords[gl_WorkGroupID.x * 3u + 2u] = _flw_visWord;
        }
        return;
    }
    if (!visible) {
        return;
    }

    uint localIndex = atomicAdd(_flw_models[modelIndex].instanceCount, 1u);
    _flw_instanceIndices[_flw_models[modelIndex].baseInstance + localIndex] = objectUint;
}
