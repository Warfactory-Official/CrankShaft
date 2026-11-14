#if defined(COMPUTE_SHADER) || (defined(_FLW_FAR) && defined(_FLW_FAR_BAKE) && !defined(_FLW_FAR_LAYER_CAPTURE))
// Actual post-material rest-space vertices, before box-relative subtraction or density can collapse a thin span.
layout(std430, binding = 0) restrict buffer FarOpaqueBoundsBuffer {
    uvec4 _flw_farOpaqueBounds[];
};

void _flw_farIncludeOpaqueVertex(uint job, vec3 position) {
    for (uint axis = 0u; axis < 3u; axis++) {
        float coordinate = position[axis] == 0.0 ? 0.0 : position[axis];
        uint value = _flw_farSortable(coordinate);
        atomicMin(_flw_farOpaqueBounds[job * 2u][axis], value);
        atomicMax(_flw_farOpaqueBounds[job * 2u + 1u][axis], value);
    }
}

// x: axis + 1, or 0 for the ordinary hull; y: the plane's exact float offset in the tile's texel frame.
uvec2 _flw_farOpaquePlane(uint job, vec3 boxMin, float density) {
    vec3 lo, hi;
    for (uint axis = 0u; axis < 3u; axis++) {
        lo[axis] = _flw_farUnsortable(_flw_farOpaqueBounds[job * 2u][axis]);
        hi[axis] = _flw_farUnsortable(_flw_farOpaqueBounds[job * 2u + 1u][axis]);
    }
    if (any(greaterThan(lo, hi))) return uvec2(0u);
    for (uint axis = 0u; axis < 3u; axis++) {
        if (lo[axis] == hi[axis]) {
            precise float offset = (lo[axis] - boxMin[axis]) * density;
            return uvec2(axis + 1u, floatBitsToUint(offset == 0.0 ? 0.0 : offset));
        }
    }
    return uvec2(0u);
}
#endif
