// GL consumers can use the current terrain producer's projection independently of their render frustum.
#ifndef _FLW_VK
uniform bool _flw_sharedDepth;
uniform bool _flw_occlusionEnabled;
uniform mat4 _flw_sharedProjection;
uniform vec2 _flw_sharedDepthSize;
uniform int _flw_sharedDepthLevels;
#endif

bool _flw_occlusionActive() {
#ifndef _FLW_VK
    return _flw_occlusionEnabled;
#else
    return true;
#endif
}

vec2 _flw_occlusionLens() {
#ifndef _FLW_VK
    if (_flw_sharedDepth) return vec2(_flw_sharedProjection[0][0], _flw_sharedProjection[1][1]);
#endif
    return vec2(_flw_cullData.P00, _flw_cullData.P11);
}

vec4 _flw_occlusionBounds(vec4 bounds) {
#ifndef _FLW_VK
    if (_flw_sharedDepth) {
        bounds -= vec4(_flw_sharedProjection[2][0], _flw_sharedProjection[2][1],
                _flw_sharedProjection[2][0], _flw_sharedProjection[2][1]) * 0.5;
    }
#endif
    return bounds;
}

vec2 _flw_occlusionSize() {
#ifndef _FLW_VK
    if (_flw_sharedDepth) return _flw_sharedDepthSize;
#endif
    return vec2(_flw_cullData.viewWidth, _flw_cullData.viewHeight);
}

int _flw_occlusionLevels() {
#ifndef _FLW_VK
    if (_flw_sharedDepth) return _flw_sharedDepthLevels;
#endif
    return _flw_cullData.pyramidLevels;
}

float _flw_occlusionSphereDepth(vec3 center, float radius) {
#ifndef _FLW_VK
    if (_flw_sharedDepth) {
        vec4 clip = _flw_sharedProjection * vec4(center.xy, center.z + radius, 1.0);
        return max(0.0, clip.z / clip.w);
    }
#endif
    return -_flw_cullData.znear / (center.z + radius);
}

float _flw_occlusionTolerance(float depth) {
#ifndef _FLW_VK
    if (_flw_sharedDepth) return max(1e-8, depth * 1e-5);
#endif
    return 0.0;
}
