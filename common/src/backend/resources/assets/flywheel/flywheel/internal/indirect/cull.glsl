#include "flywheel:internal/indirect/buffer_bindings.glsl"
#include "flywheel:internal/indirect/model_descriptor.glsl"
#include "flywheel:internal/uniforms/uniforms.glsl"
#include "flywheel:util/matrix.glsl"
#include "flywheel:internal/indirect/matrices.glsl"

layout(local_size_x = 32) in;

layout(std430, binding = _FLW_DRAW_INSTANCE_INDEX_BUFFER_BINDING) restrict writeonly buffer TargetBuffer {
    uint _flw_instanceIndices[];
};

// 4 uints/page: modelIndex, validBits, baseUint (object-buffer uint offset; mixed strides), typeId | strideUints<<16.
layout(std430, binding = _FLW_PAGE_FRAME_DESCRIPTOR_BUFFER_BINDING) restrict readonly buffer PageFrameDescriptorBuffer {
    uint _flw_pageFrameDescriptors[];
};

layout(std430, binding = _FLW_MODEL_BUFFER_BINDING) restrict buffer ModelBuffer {
    ModelDescriptor _flw_models[];
};

layout(std430, binding = _FLW_MATRIX_BUFFER_BINDING) restrict readonly buffer MatrixBuffer {
    Matrices _flw_matrices[];
};

layout(binding = 10) uniform sampler2D _flw_depthPyramid;
#include "flywheel:internal/indirect/scene_occlusion.glsl"

// Disgustingly vectorized sphere frustum intersection taking advantage of ahead of time packing.
// Only uses 6 fmas and some boolean ops.
// See also:
// flywheel:uniform/flywheel.glsl
// dev.engine_room.flywheel.lib.math.MatrixMath.writePackedFrustumPlanes
// org.joml.FrustumIntersection.testSphere
// Packed-plane sphere test; see MatrixMath.writePackedFrustumPlanes and FrustumIntersection.testSphere.
bool _flw_testSphere(vec3 center, float radius) {
    bvec4 xyInside = greaterThanEqual(fma(flw_frustumPlanes.xyX, center.xxxx, fma(flw_frustumPlanes.xyY, center.yyyy, fma(flw_frustumPlanes.xyZ, center.zzzz, flw_frustumPlanes.xyW))), -radius.xxxx);
    bvec2 zInside = greaterThanEqual(fma(flw_frustumPlanes.zX, center.xx, fma(flw_frustumPlanes.zY, center.yy, fma(flw_frustumPlanes.zZ, center.zz, flw_frustumPlanes.zW))), -radius.xx);

    return all(xyInside) && all(zInside);
}

bool projectSphere(vec3 c, float r, float znear, float P00, float P11, out vec4 aabb) {
    // Closest point on the sphere is between the camera and the near plane, don't even attempt to cull.
    if (c.z + r > -znear) {
        return false;
    }

    vec3 cr = c * r;
    float czr2 = c.z * c.z - r * r;

    float vx = sqrt(c.x * c.x + czr2);
    float minx = (vx * c.x - cr.z) / (vx * c.z + cr.x);
    float maxx = (vx * c.x + cr.z) / (vx * c.z - cr.x);

    float vy = sqrt(c.y * c.y + czr2);
    float miny = (vy * c.y - cr.z) / (vy * c.z + cr.y);
    float maxy = (vy * c.y + cr.z) / (vy * c.z - cr.y);

    aabb = vec4(minx * P00, miny * P11, maxx * P00, maxy * P11);
    aabb = aabb.xwzy * vec4(-0.5f, -0.5f, -0.5f, -0.5f) + vec4(0.5f); // clip space -> uv space

    return true;
}

// Port: per page slot: pass-1 visible bits, pass-1 frustum bits, this pass's visible bits of an ordered model.
layout(std430, binding = _FLW_VIS_WORDS_BUFFER_BINDING) restrict buffer _FlwVisWordsBuffer {
    uint _flw_visWords[];
};
shared uint _flw_visWord;
#ifdef _FLW_CULL_VIS_OUT
shared uint _flw_frustumWord;
#endif

// Farthest occluder over the min-reduced pyramid; no pixel center covered -> returns 2.0, caller culls.
float _flw_hizOccluder(vec4 aabb) {
    vec2 viewSize = _flw_occlusionSize();
    vec4 uv = clamp(vec4(min(aabb.xy, aabb.zw), max(aabb.xy, aabb.zw)), 0.0, 1.0);
    ivec4 rect = ivec4(uv * viewSize.xyxy + vec4(0.5, 0.5, -0.5, -0.5));
    if (any(lessThan(rect.zw, rect.xy))) {
        return 2.0;
    }
    rect = min(rect, ivec4(viewSize.xyxy) - 1) >> 1;

    ivec2 extent = rect.zw - rect.xy;
    int level = max(findMSB(max(extent.x, extent.y)), 0);
    level += any(greaterThan((rect.zw >> level) - (rect.xy >> level), ivec2(1))) ? 1 : 0;
    level = min(level, _flw_occlusionLevels());

    ivec4 bounds = rect >> level;

    float depth01 = texelFetch(_flw_depthPyramid, bounds.xw, level).r;
    float depth11 = texelFetch(_flw_depthPyramid, bounds.zw, level).r;
    float depth10 = texelFetch(_flw_depthPyramid, bounds.zy, level).r;
    float depth00 = texelFetch(_flw_depthPyramid, bounds.xy, level).r;

    return min(min(depth00, depth01), min(depth10, depth11));
}

bool _flw_isVisible(uint objectUint, uint modelIndex, uint typeId, out bool frustumVisible) {
    uint matrixIndexRaw = _flw_models[modelIndex].matrixIndex;
    uint matrixIndex = matrixIndexRaw & _FLW_MODEL_MATRIX_MASK;
    BoundingSphere sphere = _flw_models[modelIndex].boundingSphere;

    vec3 center;
    float radius;
    _flw_unpackBoundingSphere(sphere, center, radius);

    _flw_transformBoundingSphereUber(typeId, objectUint, center, radius);

    if (matrixIndex > 0) {
        transformBoundingSphere(_flw_matrices[matrixIndex].pose, center, radius);
    }

#ifdef _FLW_CULL_PASS2
    bool isVisible = true;
#else
    bool isVisible = _flw_testSphere(center, radius);
#endif
    frustumVisible = isVisible;

#ifndef _FLW_CULL_FRUSTUM_ONLY
    if (isVisible && (matrixIndexRaw & _FLW_MODEL_NO_OCCLUSION) == 0u && _flw_occlusionActive()) {
        transformBoundingSphere(flw_view, center, radius);

        vec4 aabb;
        vec2 lens = _flw_occlusionLens();
        if (projectSphere(center, radius, _flw_cullData.znear, lens.x, lens.y, aabb))
        {
            // 26.2: reversed-Z, min-reduced pyramid -- occluder = MIN sample, nearest-point depth = -znear/z, test flips to >=.
            float depth = _flw_hizOccluder(_flw_occlusionBounds(aabb));

            float depthSphere = _flw_occlusionSphereDepth(center, radius);

            isVisible = isVisible && depthSphere + _flw_occlusionTolerance(depthSphere) >= depth;
        }
    }
#endif

    return isVisible;
}

void main() {
    uint pageIndex = gl_WorkGroupID.x << 2u;

    // Port: MoltenVK 1.4 push-descriptor length() = 0.
#ifndef _FLW_VK
    if (pageIndex >= _flw_pageFrameDescriptors.length()) {
        return;
    }
#endif

    uint modelIndex = _flw_pageFrameDescriptors[pageIndex];

    uint pageValidity = _flw_pageFrameDescriptors[pageIndex + 1];

    uint baseUint = _flw_pageFrameDescriptors[pageIndex + 2];

    uint typeInfo = _flw_pageFrameDescriptors[pageIndex + 3];

    uint objectUint = baseUint + gl_LocalInvocationID.x * (typeInfo >> 16u);

    // Uniform per workgroup; a free slot carries no valid bits.
    bool ordered = pageValidity != 0u && (_flw_models[modelIndex].matrixIndex & _FLW_MODEL_ORDERED) != 0u;
#ifdef _FLW_CULL_VIS_OUT
    bool wordsOut = true;
#else
    bool wordsOut = ordered;
#endif

#if defined(_FLW_HAS_SUBGROUP) && _FLW_SUBGROUP_SIZE == 32
    // 32-wide subgroup = workgroup: ballot verifies the lane mapping; else shared-memory fallback (uniform flow).
    bool _flw_wgIsSubgroup = subgroupBallot(gl_SubgroupInvocationID == gl_LocalInvocationID.x).x == 0xFFFFFFFFu;
#else
    bool _flw_wgIsSubgroup = false;
#endif
    if (wordsOut && !_flw_wgIsSubgroup) {
        if (gl_LocalInvocationID.x == 0u) {
            _flw_visWord = 0u;
#ifdef _FLW_CULL_VIS_OUT
            _flw_frustumWord = 0u;
#endif
        }
        barrier();
    }

    bool frustumVisible = false;
    bool visible = ((1u << gl_LocalInvocationID.x) & pageValidity) != 0
#ifdef _FLW_CULL_PASS2
            && ((_flw_visWords[gl_WorkGroupID.x * 3u] >> gl_LocalInvocationID.x) & 1u) == 0u
            && ((_flw_visWords[gl_WorkGroupID.x * 3u + 1u] >> gl_LocalInvocationID.x) & 1u) == 1u
#endif
            && _flw_isVisible(objectUint, modelIndex, typeInfo & 0xFFFFu, frustumVisible);

#ifdef _FLW_HAS_SUBGROUP
    uvec4 ballot = subgroupBallot(visible);
#endif

    if (wordsOut) {
        uint visWord = 0u;
#ifdef _FLW_CULL_VIS_OUT
        uint frustumWord = 0u;
#endif
#if defined(_FLW_HAS_SUBGROUP) && _FLW_SUBGROUP_SIZE == 32
        if (_flw_wgIsSubgroup) {
            visWord = ballot.x;
#ifdef _FLW_CULL_VIS_OUT
            frustumWord = subgroupBallot(frustumVisible).x;
#endif
        }
#endif
        if (!_flw_wgIsSubgroup) {
            if (visible) {
                atomicOr(_flw_visWord, 1u << gl_LocalInvocationID.x);
            }
#ifdef _FLW_CULL_VIS_OUT
            if (frustumVisible) {
                atomicOr(_flw_frustumWord, 1u << gl_LocalInvocationID.x);
            }
#endif
            barrier();
            visWord = _flw_visWord;
#ifdef _FLW_CULL_VIS_OUT
            frustumWord = _flw_frustumWord;
#endif
        }
        if (gl_LocalInvocationID.x == 0u) {
#ifdef _FLW_CULL_VIS_OUT
            _flw_visWords[gl_WorkGroupID.x * 3u] = visWord;
            _flw_visWords[gl_WorkGroupID.x * 3u + 1u] = frustumWord;
#endif
            if (ordered) {
                _flw_visWords[gl_WorkGroupID.x * 3u + 2u] = visWord;
            }
        }
    }
    if (ordered) {
        return;
    }

#ifdef _FLW_HAS_SUBGROUP
    uint count = subgroupBallotBitCount(ballot);
    uint base = 0u;
    if (count != 0u && subgroupElect()) {
        base = atomicAdd(_flw_models[modelIndex].instanceCount, count);
    }
    base = subgroupBroadcastFirst(base);
    if (visible) {
        uint targetIndex = _flw_models[modelIndex].baseInstance + base + subgroupBallotExclusiveBitCount(ballot);
        _flw_instanceIndices[targetIndex] = objectUint;
    }
#else
    if (visible) {
        uint localIndex = atomicAdd(_flw_models[modelIndex].instanceCount, 1);
        uint targetIndex = _flw_models[modelIndex].baseInstance + localIndex;
        _flw_instanceIndices[targetIndex] = objectUint;
    }
#endif
}
