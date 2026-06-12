// SPDX-License-Identifier: MIT
// Conservative texel-gather HiZ

const float _FLW_HIZ_ADD_BLOCKS = 0.1;

bool _flw_hizAabbVisible(vec3 mins, vec3 maxs, vec3 cam, mat4 viewProj, vec2 viewSize, sampler2D pyramid) {
    if (all(greaterThanEqual(cam + _FLW_HIZ_ADD_BLOCKS, mins))
            && all(lessThanEqual(cam - _FLW_HIZ_ADD_BLOCKS, maxs))) {
        return true;
    }

    vec2 uvMin = vec2(1e30);
    vec2 uvMax = vec2(-1e30);
    float nearDepth = 0.0; // reversed-Z: nearest corner = MAX ndc.z
    vec4 clipBase = viewProj * vec4(mins, 1.0);
    vec4 clipDx = viewProj[0] * (maxs.x - mins.x);
    vec4 clipDy = viewProj[1] * (maxs.y - mins.y);
    vec4 clipDz = viewProj[2] * (maxs.z - mins.z);
    for (uint c = 0u; c < 8u; c++) {
        vec4 clip = clipBase
                + (((c & 1u) != 0u) ? clipDx : vec4(0.0))
                + (((c & 2u) != 0u) ? clipDy : vec4(0.0))
                + (((c & 4u) != 0u) ? clipDz : vec4(0.0));
        if (clip.w <= 1e-6) {
            return true;
        }
        vec3 ndc = clip.xyz / clip.w;
        uvMin = min(uvMin, ndc.xy * 0.5 + 0.5);
        uvMax = max(uvMax, ndc.xy * 0.5 + 0.5);
        nearDepth = max(nearDepth, ndc.z);
    }

    if (uvMax.x < 0.0 || uvMin.x > 1.0 || uvMax.y < 0.0 || uvMin.y > 1.0) {
        return false;
    }
    uvMin = clamp(uvMin, vec2(0.0), vec2(1.0));
    uvMax = clamp(uvMax, vec2(0.0), vec2(1.0));

    // Pixel-center footprint + findMSB mip pick (rationale in cull.glsl); no pixel center covered -> draws nothing.
    ivec4 rect = ivec4(vec4(uvMin, uvMax) * viewSize.xyxy + vec4(0.5, 0.5, -0.5, -0.5));
    if (any(lessThan(rect.zw, rect.xy))) {
        return false;
    }
    rect = min(rect, ivec4(viewSize.xyxy) - 1) >> 1;
    ivec2 extent = rect.zw - rect.xy;
    int level = max(findMSB(max(extent.x, extent.y)), 0);
    level += any(greaterThan((rect.zw >> level) - (rect.xy >> level), ivec2(1))) ? 1 : 0;
    level = min(level, textureQueryLevels(pyramid) - 1);

    ivec4 bounds = rect >> level;

    float d00 = texelFetch(pyramid, bounds.xy, level).r;
    float d10 = texelFetch(pyramid, bounds.zy, level).r;
    float d01 = texelFetch(pyramid, bounds.xw, level).r;
    float d11 = texelFetch(pyramid, bounds.zw, level).r;
    float occluder = min(min(d00, d10), min(d01, d11));

    // reversed-Z: nearest corner visible iff >= the farthest occluder; equality never over-culls.
    return nearDepth >= occluder;
}
