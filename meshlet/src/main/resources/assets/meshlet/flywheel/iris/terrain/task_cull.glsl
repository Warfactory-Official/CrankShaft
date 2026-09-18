uniform sampler2D _flw_taskDepth;

float _flw_winding(vec4 a, vec4 b, vec4 c) {
    vec2 ab = b.xy / b.w - a.xy / a.w;
    vec2 ac = c.xy / c.w - a.xy / a.w;
    return ab.x * ac.y - ab.y * ac.x;
}

// 0 needs current-depth recovery, 1 is visible, 2 cannot become visible again in this terrain call.
uint _flw_quadVisibility(vec4 a, vec4 b, vec4 c, vec4 d) {
    if (_flw_taskCull == 0u) return 1u;
    // A near-plane crossing needs homogeneous clipping; leave it to the rasterizer.
    if (min(min(a.w, b.w), min(c.w, d.w)) <= 1e-5) return 1u;
    vec3 lo = min(min(a.xyz / a.w, b.xyz / b.w), min(c.xyz / c.w, d.xyz / d.w));
    vec3 hi = max(max(a.xyz / a.w, b.xyz / b.w), max(c.xyz / c.w, d.xyz / d.w));
    if (any(lessThan(hi, vec3(-1.00001, -1.00001, -0.00001)))
            || any(greaterThan(lo, vec3(1.00001)))) return 2u;
    // Both triangles must be back-facing. The epsilon retains near-degenerate faces conservatively.
    if ((_flw_taskCull & 2u) != 0u && _flw_winding(a, b, c) < -1e-8
            && _flw_winding(c, d, a) < -1e-8) return 2u;
#ifndef _FLW_TASK_DEPTH_SAFE
    return 1u;
#else
    if ((_flw_taskCull & 4u) == 0u || lo.z < 0.0 || hi.z > 1.0) return 1u;
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
    if (any(greaterThan(bounds.zw - bounds.xy, ivec2(1)))) return 1u;
#ifdef _FLW_FORCE_RECOVERY
    if (_flw_taskPhase == 1u) return 0u;
#endif
    float farthest = min(min(texelFetch(_flw_taskDepth, bounds.xy, level).r,
                             texelFetch(_flw_taskDepth, bounds.zy, level).r),
                         min(texelFetch(_flw_taskDepth, bounds.xw, level).r,
                             texelFetch(_flw_taskDepth, bounds.zw, level).r));
    return hi.z + 1e-5 >= farthest ? 1u : 0u;
#endif
}
