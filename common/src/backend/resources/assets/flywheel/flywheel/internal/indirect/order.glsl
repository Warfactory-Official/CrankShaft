#include "flywheel:internal/indirect/buffer_bindings.glsl"
#include "flywheel:internal/indirect/model_descriptor.glsl"

// Compacts each ordered model's visible instances in page order after a cull pass: blended draws then submit in
// the order they were created, every frame.

layout(std430, binding = _FLW_PAGE_FRAME_DESCRIPTOR_BUFFER_BINDING) restrict readonly buffer PageFrameDescriptorBuffer {
    uint _flw_pageFrameDescriptors[];
};

// Per ordered model: modelIndex, first entry in _flw_orderedPages, page count, instance stride in uints.
layout(std430, binding = _FLW_ORDERED_MODEL_BUFFER_BINDING) restrict readonly buffer OrderedModelBuffer {
    uvec4 _flw_orderedModels[];
};

// The model's page slots in page order; ~0u: a page without a slot.
layout(std430, binding = _FLW_ORDERED_PAGE_BUFFER_BINDING) restrict readonly buffer OrderedPageBuffer {
    uint _flw_orderedPages[];
};

layout(std430, binding = _FLW_DRAW_INSTANCE_INDEX_BUFFER_BINDING) restrict writeonly buffer TargetBuffer {
    uint _flw_instanceIndices[];
};

layout(std430, binding = _FLW_MODEL_BUFFER_BINDING) restrict buffer ModelBuffer {
    ModelDescriptor _flw_models[];
};

layout(std430, binding = _FLW_VIS_WORDS_BUFFER_BINDING) restrict readonly buffer _FlwVisWordsBuffer {
    uint _flw_visWords[];
};

#include "flywheel:internal/indirect/order_scan.glsl"

uint _flw_orderPageBase;
uint _flw_orderTarget;

uint _flw_orderedPage(uint page, out uint firstObject) {
    uint slot = _flw_orderedPages[_flw_orderPageBase + page];
    if (slot == 0xFFFFFFFFu) {
        firstObject = 0u;
        return 0u;
    }
    firstObject = _flw_pageFrameDescriptors[(slot << 2u) + 2u];
    return _flw_visWords[slot * 3u + 2u];
}

void _flw_orderedEmit(uint rank, uint objectUint) {
    _flw_instanceIndices[_flw_orderTarget + rank] = objectUint;
}

void main() {
    uvec4 ordered = _flw_orderedModels[gl_WorkGroupID.x];
    _flw_orderPageBase = ordered.y;
    _flw_orderTarget = _flw_models[ordered.x].baseInstance;
    uint count = _flw_orderedCompact(ordered.z, ordered.w);
    if (gl_LocalInvocationID.x == 0u) {
        _flw_models[ordered.x].instanceCount = count;
    }
}
