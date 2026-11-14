#include "flywheel:internal/indirect/model_descriptor.glsl"
#include "flywheel:internal/far/far.glsl"
#include "flywheel:internal/far/row_box.glsl"

layout(local_size_x = 64) in;

// objectUint, model index, type id, index of the row's part among this pass's new parts.
layout(std430, binding = 0) restrict readonly buffer NewRowBuffer {
    uvec4 _flw_farNewRows[];
};

layout(std430, binding = 3) restrict readonly buffer ModelBuffer {
    ModelDescriptor _flw_models[];
};

layout(std430, binding = 9) restrict buffer EntryBuffer {
    FarEntry _flw_farEntries[];
};

// Per new part: sortable bounds of its rows in its rest pose.
layout(std430, binding = 10) restrict buffer NewPartBoundsBuffer {
    uvec4 _flw_farNewPartBounds[];
};

void _flw_farGrowEntry(uint entry, vec3 center, float radius) {
    atomicMin(_flw_farEntries[entry].boundsMin.x, _flw_farSortable(center.x - radius));
    atomicMin(_flw_farEntries[entry].boundsMin.y, _flw_farSortable(center.y - radius));
    atomicMin(_flw_farEntries[entry].boundsMin.z, _flw_farSortable(center.z - radius));
    atomicMax(_flw_farEntries[entry].boundsMax.x, _flw_farSortable(center.x + radius));
    atomicMax(_flw_farEntries[entry].boundsMax.y, _flw_farSortable(center.y + radius));
    atomicMax(_flw_farEntries[entry].boundsMax.z, _flw_farSortable(center.z + radius));
}

void _flw_farGrowPart(uint part, vec3 lo, vec3 hi) {
    atomicMin(_flw_farNewPartBounds[part * 2u].x, _flw_farSortable(lo.x));
    atomicMin(_flw_farNewPartBounds[part * 2u].y, _flw_farSortable(lo.y));
    atomicMin(_flw_farNewPartBounds[part * 2u].z, _flw_farSortable(lo.z));
    atomicMax(_flw_farNewPartBounds[part * 2u + 1u].x, _flw_farSortable(hi.x));
    atomicMax(_flw_farNewPartBounds[part * 2u + 1u].y, _flw_farSortable(hi.y));
    atomicMax(_flw_farNewPartBounds[part * 2u + 1u].z, _flw_farSortable(hi.z));
}

// Grows each new row's entry bounds by the row's sphere (a spinning row's by its sweep about the pivot) and its part's
// rest-pose bounds by its rest-pose box.
void main() {
    uint index = gl_GlobalInvocationID.x;
    if (index >= _flw_farCount) {
        return;
    }
    uvec4 row = _flw_farNewRows[index];
    vec3 center;
    float radius;
    _flw_unpackBoundingSphere(_flw_models[row.y].boundingSphere, center, radius);
    _flw_transformBoundingSphereUber(row.z, row.x, center, radius);

    FarPart part = _flw_farParts[_flw_farRowPart(row.x)];
    _flw_farFrameSphere(part, center, radius);
    if (part.pivot.w != 0.0) {
        radius += distance(center, part.pivot.xyz);
        center = part.pivot.xyz;
    }
    _flw_farGrowEntry(part.info.x, center, radius);

    vec3 lo;
    vec3 hi;
    _flw_farRowBox(row, part, lo, hi);
    _flw_farGrowPart(row.w, lo, hi);
}
