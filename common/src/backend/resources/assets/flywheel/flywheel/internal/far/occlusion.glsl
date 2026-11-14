layout(binding = 10) uniform sampler2D _flw_farOcclusionDepth;

// The pyramid starts with a 2x2 MIN reduction of the full image; padded texels contain clear reversed-Z depth.
float _flw_farOccluder(vec4 aabb) {
    vec2 viewSize = _flw_farOcclusionSize.xy;
    vec2 padding = 1.0 / viewSize;
    vec4 uv = clamp(aabb + vec4(-padding, padding), 0.0, 1.0);
    ivec4 rect = ivec4(uv * viewSize.xyxy + vec4(0.5, 0.5, -0.5, -0.5));
    if (any(lessThan(rect.zw, rect.xy))) {
        return 2.0;
    }
    rect = min(rect, ivec4(viewSize.xyxy) - 1) >> 1;

    ivec2 extent = rect.zw - rect.xy;
    int level = max(findMSB(max(extent.x, extent.y)), 0);
    level += any(greaterThan((rect.zw >> level) - (rect.xy >> level), ivec2(1))) ? 1 : 0;
    level = min(level, int(_flw_farOcclusionSize.z));
    ivec4 bounds = rect >> level;

    float depth01 = texelFetch(_flw_farOcclusionDepth, bounds.xw, level).r;
    float depth11 = texelFetch(_flw_farOcclusionDepth, bounds.zw, level).r;
    float depth10 = texelFetch(_flw_farOcclusionDepth, bounds.zy, level).r;
    float depth00 = texelFetch(_flw_farOcclusionDepth, bounds.xy, level).r;
    return min(min(depth00, depth01), min(depth10, depth11));
}

// Project the current bounds through the same frame's occlusion view. Near-plane crossings stay visible because
// clipping can enlarge their screen footprint beyond the projected corners.
bool _flw_farOccluded(vec3 lo, vec3 hi) {
    if (_flw_farOcclusionSize.w == 0.0) {
        return false;
    }
    vec2 screenLo = vec2(1e30);
    vec2 screenHi = vec2(-1e30);
    float nearest = 0.0;
    for (int corner = 0; corner < 8; corner++) {
        vec3 position = mix(lo, hi, bvec3((corner & 1) != 0, (corner & 2) != 0, (corner & 4) != 0));
        vec4 clip = _flw_farOcclusionClip * vec4(position, 1.0);
        if (clip.w <= 0.0) {
            return false;
        }
        vec3 projected = clip.xyz / clip.w;
        nearest = max(nearest, projected.z);
        vec2 uv = projected.xy * 0.5 + 0.5;
        screenLo = min(screenLo, uv);
        screenHi = max(screenHi, uv);
    }
    if (nearest > 1.0) {
        return false;
    }
    float occluder = _flw_farOccluder(vec4(screenLo, screenHi));
    float tolerance = max(1e-8, nearest * 1e-5);
    return nearest + tolerance < occluder;
}

bool _flw_farSphereOccluded(vec3 center, float radius) {
    return _flw_farOccluded(center - radius, center + radius);
}
