// Iris's light frustum, including its distance box and safe zone; supplied by GuestShadowCull.
layout(std140, binding = 13) uniform _FlwShadowCull {
    vec4 _flw_shadowPlanes[13];
    vec3 _flw_shadowOriginOffset;
    int _flw_shadowPlaneCount;
    float _flw_shadowDistance;
    float _flw_shadowSafeZone;
};

bool _flw_sphereOutsideBox(vec3 center, float radius, float halfExtent) {
    return any(greaterThan(abs(center) - radius, vec3(halfExtent)));
}

bool _flw_shadowVisible(vec3 center, float radius) {
    if (_flw_shadowDistance > 0.0 && _flw_sphereOutsideBox(center, radius, _flw_shadowDistance)) return false;
    if (_flw_shadowSafeZone > 0.0 && !_flw_sphereOutsideBox(center, radius, _flw_shadowSafeZone)) return true;
    for (int i = 0; i < _flw_shadowPlaneCount; i++) {
        vec4 plane = _flw_shadowPlanes[i];
        if (dot(plane.xyz, center) + plane.w < -radius * length(plane.xyz)) return false;
    }
    return true;
}
