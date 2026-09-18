#include "meshlet:iris/terrain/task_depth.glsl"

float _flw_winding(vec4 a, vec4 b, vec4 c) {
    vec2 ab = b.xy / b.w - a.xy / a.w;
    vec2 ac = c.xy / c.w - a.xy / a.w;
    return ab.x * ac.y - ab.y * ac.x;
}

// x: mip and status (0 temporal reject, 1 visible, 2 geometric reject); yz: exact texel rectangle; w: nearest Z.
uvec4 _flw_quadVisibility(vec4 a, vec4 b, vec4 c, vec4 d) {
    if (_flw_taskCull == 0u) return uvec4(1, 0, 0, 0);
    // A near-plane crossing needs homogeneous clipping; leave it to the rasterizer.
    if (min(min(a.w, b.w), min(c.w, d.w)) <= 1e-5) return uvec4(1, 0, 0, 0);
    vec3 lo = min(min(a.xyz / a.w, b.xyz / b.w), min(c.xyz / c.w, d.xyz / d.w));
    vec3 hi = max(max(a.xyz / a.w, b.xyz / b.w), max(c.xyz / c.w, d.xyz / d.w));
    if (any(lessThan(hi, vec3(-1.00001, -1.00001, -0.00001)))
            || any(greaterThan(lo, vec3(1.00001)))) return uvec4(2, 0, 0, 0);
    // Both triangles must be back-facing. The epsilon retains near-degenerate faces conservatively.
    if ((_flw_taskCull & 2u) != 0u && _flw_winding(a, b, c) < -1e-8
            && _flw_winding(c, d, a) < -1e-8) return uvec4(2, 0, 0, 0);
#ifndef _FLW_TASK_DEPTH_SAFE
    return uvec4(1, 0, 0, 0);
#else
    if ((_flw_taskCull & 4u) == 0u || lo.z < 0.0 || hi.z > 1.0) return uvec4(1, 0, 0, 0);
    vec2 uvMin = clamp(lo.xy * 0.5 + 0.5, vec2(0.0), vec2(1.0));
    vec2 uvMax = clamp(hi.xy * 0.5 + 0.5, vec2(0.0), vec2(1.0));
    // Include boundary texels; a larger footprint can only make a MIN pyramid less occluding.
    ivec4 rect = ivec4(floor(vec4(uvMin, uvMax) * vec4(_flw_viewSize.xyxy)));
    rect = clamp(rect, ivec4(0), _flw_viewSize.xyxy - 1) >> 1;
    ivec2 extent = rect.zw - rect.xy;
    int level = max(findMSB(max(extent.x, extent.y)), 0);
    level += any(greaterThan((rect.zw >> level) - (rect.xy >> level), ivec2(1))) ? 1 : 0;
    level = min(level, textureQueryLevels(_flw_taskDepth) - 1);
    ivec4 bounds = rect >> level;
    // A rectangular pyramid can end before the footprint fits the four corner samples.
    if (any(greaterThan(bounds.zw - bounds.xy, ivec2(1)))) return uvec4(1, 0, 0, 0);
    uvec4 test = uvec4(uint(level) << 2u, uint(bounds.x) | (uint(bounds.y) << 16u),
            uint(bounds.z) | (uint(bounds.w) << 16u), floatBitsToUint(hi.z));
#ifdef _FLW_FORCE_RECOVERY
    if (_flw_taskPhase == 1u) return test;
#endif
    test.x |= _flw_boundsVisible(test) ? 1u : 0u;
    return test;
#endif
}
