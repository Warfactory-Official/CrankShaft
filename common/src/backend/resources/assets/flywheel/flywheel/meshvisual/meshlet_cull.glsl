// gl_mesh_shader task-cull helpers. Included ONLY by the task stage (visual_task.task).
#include "flywheel:meshvisual/cull_uniforms.glsl"
#include "flywheel:internal/indirect/scene_occlusion.glsl"

layout(std430, binding = 16) restrict readonly buffer _FlwMeshletBoundsBuffer {
    vec4 _flw_meshletBounds[];
};

layout(binding = 10) uniform sampler2D _flw_depthPyramid;

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
    if (!_flw_occlusionActive()) return true;

    vec3 c = center;
    float r = radius;
    transformBoundingSphere(flw_view, c, r);

    vec4 aabb;
    vec2 lens = _flw_occlusionLens();
    if (_flw_projectSphere(c, r, _flw_cullData.znear, lens.x, lens.y, aabb)) {
        aabb = _flw_occlusionBounds(aabb);
        vec2 viewSize = _flw_occlusionSize();
        vec4 uv = clamp(vec4(min(aabb.xy, aabb.zw), max(aabb.xy, aabb.zw)), 0.0, 1.0);
        ivec4 rect = ivec4(uv * viewSize.xyxy + vec4(0.5, 0.5, -0.5, -0.5));
        if (any(lessThan(rect.zw, rect.xy))) {
            return false;
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

        // Reversed-Z MIN-reduced pyramid: occluder is the MIN sample; cull only when the nearest point is behind it.
        float depth = min(min(depth00, depth01), min(depth10, depth11));
        float depthSphere = _flw_occlusionSphereDepth(c, r);

        if (depthSphere + _flw_occlusionTolerance(depthSphere) < depth) {
            return false;
        }
    }

    return true;
}
