// VK twin of meshlet_cull.glsl: frame view at binding 8, HiZ at 23, bounds by device address (_flw_boundsAddr).
struct FrustumPlanes {
    vec4 xyX;
    vec4 xyY;
    vec4 xyZ;
    vec4 xyW;
    vec2 zX;
    vec2 zY;
    vec2 zZ;
    vec2 zW;
};
struct _FlwCullData {
    float znear;
    float zfar;
    float P00;
    float P11;
    float viewWidth;
    float viewHeight;
    int pyramidLevels;
    uint useMin;
};
layout(std140, binding = 8) uniform _FlwFrameUniforms {
    FrustumPlanes flw_frustumPlanes;
    _FlwCullData _flw_cullData;
    mat4 flw_view;
};

layout(binding = 23) uniform sampler2D _flw_depthPyramid;

layout(buffer_reference, std430, buffer_reference_align = 16) readonly buffer _FlwMeshletBoundsRef {
    vec4 v[];
};

bool _flw_testSphere(vec3 center, float radius) {
    bvec4 xyInside = greaterThanEqual(fma(flw_frustumPlanes.xyX, center.xxxx, fma(flw_frustumPlanes.xyY, center.yyyy, fma(flw_frustumPlanes.xyZ, center.zzzz, flw_frustumPlanes.xyW))), -radius.xxxx);
    bvec2 zInside = greaterThanEqual(fma(flw_frustumPlanes.zX, center.xx, fma(flw_frustumPlanes.zY, center.yy, fma(flw_frustumPlanes.zZ, center.zz, flw_frustumPlanes.zW))), -radius.xx);

    return all(xyInside) && all(zInside);
}

bool _flw_projectSphere(vec3 c, float r, float znear, float P00, float P11, out vec4 aabb) {
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
    aabb = aabb.xwzy * vec4(-0.5f, -0.5f, -0.5f, -0.5f) + vec4(0.5f);

    return true;
}

bool _flw_meshletVisible(vec3 center, float radius) {
    if (!_flw_testSphere(center, radius)) {
        return false;
    }

    vec3 c = center;
    float r = radius;
    transformBoundingSphere(flw_view, c, r);

    vec4 aabb;
    if (_flw_projectSphere(c, r, _flw_cullData.znear, _flw_cullData.P00, _flw_cullData.P11, aabb)) {
        vec2 viewSize = vec2(_flw_cullData.viewWidth, _flw_cullData.viewHeight);
        vec4 uv = clamp(vec4(min(aabb.xy, aabb.zw), max(aabb.xy, aabb.zw)), 0.0, 1.0);
        ivec4 rect = ivec4(uv * viewSize.xyxy + vec4(0.5, 0.5, -0.5, -0.5));
        if (any(lessThan(rect.zw, rect.xy))) {
            return false;
        }
        rect = min(rect, ivec4(viewSize.xyxy) - 1) >> 1;

        ivec2 extent = rect.zw - rect.xy;
        int level = max(findMSB(max(extent.x, extent.y)), 0);
        level += any(greaterThan((rect.zw >> level) - (rect.xy >> level), ivec2(1))) ? 1 : 0;
        level = min(level, _flw_cullData.pyramidLevels);

        ivec4 bounds = rect >> level;

        float depth01 = texelFetch(_flw_depthPyramid, bounds.xw, level).r;
        float depth11 = texelFetch(_flw_depthPyramid, bounds.zw, level).r;
        float depth10 = texelFetch(_flw_depthPyramid, bounds.zy, level).r;
        float depth00 = texelFetch(_flw_depthPyramid, bounds.xy, level).r;

        float depth = min(min(depth00, depth01), min(depth10, depth11));
        float depthSphere = -_flw_cullData.znear / (c.z + r);

        if (depthSphere < depth) {
            return false;
        }
    }

    return true;
}
