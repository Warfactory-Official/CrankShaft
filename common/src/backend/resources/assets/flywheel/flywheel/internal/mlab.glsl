layout(std140, binding = 26) uniform _FlwMlabUniforms {
    uvec2 _flw_mlabSize;
    uint _flw_mlabMaxNodes;
    uint _flw_mlabK;
    uint _flw_mlabLayerMask;
};

uint _flw_mlabPixelIndex() {
    return uint(gl_FragCoord.y) * _flw_mlabSize.x + uint(gl_FragCoord.x);
}

uint _flw_mlabPixelCount() {
    return _flw_mlabSize.x * _flw_mlabSize.y;
}

#ifndef _FLW_MLAB_WINDOW
#define _FLW_MLAB_WINDOW 8u
#endif

#ifdef _FLW_OIT_KBUFFER

#ifdef _FLW_MLAB_PRODUCER
layout(std430, binding = 24) restrict buffer _FlwMlabCount { uint _flw_mlabCount[]; };
layout(std430, binding = 25) restrict buffer _FlwMlabData { uvec2 _flw_mlabData[]; };
#else
layout(std430, binding = 24) readonly restrict buffer _FlwMlabCount { uint _flw_mlabCount[]; };
layout(std430, binding = 25) readonly restrict buffer _FlwMlabData { uvec2 _flw_mlabData[]; };
#endif

#ifdef _FLW_MLAB_PRODUCER
layout(early_fragment_tests) in;

void _flw_mlabInsertPremul(vec3 rgb, float a, float deviceZ) {
    uint p = _flw_mlabPixelIndex();
    uint slot = atomicAdd(_flw_mlabCount[p], 1u);
    if (slot < _flw_mlabK) {
        _flw_mlabData[p * _flw_mlabK + slot] = uvec2(floatBitsToUint(deviceZ), packUnorm4x8(vec4(rgb, a)));
    }
}
void _flw_mlabInsert(vec4 color, float deviceZ) { _flw_mlabInsertPremul(color.rgb * color.a, color.a, deviceZ); }
#endif

#endif

#ifdef _FLW_OIT_MLAB

#ifdef _FLW_MLAB_PRODUCER
layout(std430, binding = 24) coherent restrict buffer _FlwMlabCount { uint _flw_mlabCount[]; };
layout(std430, binding = 25) coherent restrict buffer _FlwMlabData { uvec2 _flw_mlabData[]; };
#else
layout(std430, binding = 24) readonly restrict buffer _FlwMlabCount { uint _flw_mlabCount[]; };
layout(std430, binding = 25) readonly restrict buffer _FlwMlabData { uvec2 _flw_mlabData[]; };
#endif

uint _flw_mlabSlot(uint p, uint layer) { return layer * _flw_mlabPixelCount() + p; }

#ifdef _FLW_MLAB_PRODUCER
layout(early_fragment_tests) in;
layout(pixel_interlock_unordered) in;

void _flw_mlabInsertSerial(uint p, uint count, uvec2 cur) {
    if (count > 0u) {
        uvec2 back = _flw_mlabData[_flw_mlabSlot(p, count - 1u)];
        if (uintBitsToFloat(cur.x) < uintBitsToFloat(back.x) && unpackUnorm4x8(back.y).a > (1.0 - _FLW_OIT_TRANSMIT_EPS)) {
            return;
        }
    }
    for (uint i = 0u; i < count; ++i) {
        uint slot = _flw_mlabSlot(p, i);
        uvec2 s = _flw_mlabData[slot];
        if (uintBitsToFloat(cur.x) > uintBitsToFloat(s.x)) {
            _flw_mlabData[slot] = cur;
            cur = s;
        }
    }
    if (count >= _flw_mlabK) {
        uint last = _flw_mlabSlot(p, _flw_mlabK - 1u);
        uvec2 lastS = _flw_mlabData[last];
        vec4 l = unpackUnorm4x8(lastS.y);
        vec4 c = unpackUnorm4x8(cur.y);
        vec4 merged = vec4(l.rgb + (1.0 - l.a) * c.rgb, 1.0 - (1.0 - l.a) * (1.0 - c.a));
        uint depth = uintBitsToFloat(lastS.x) > uintBitsToFloat(cur.x) ? lastS.x : cur.x;
        _flw_mlabData[last] = uvec2(depth, packUnorm4x8(merged));
    } else {
        _flw_mlabData[_flw_mlabSlot(p, count)] = cur;
        _flw_mlabCount[p] = count + 1u;
    }
}

void _flw_mlabInsertWindow(uint p, uint count, uvec2 cur) {
    uvec2 s[_FLW_MLAB_WINDOW];
    for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
        if (i < count) {
            s[i] = _flw_mlabData[_flw_mlabSlot(p, i)];
        }
    }
    if (count > 0u) {
        uvec2 back = s[0];
        for (uint i = 1u; i < _FLW_MLAB_WINDOW; ++i) {
            if (i < count) {
                back = s[i];
            }
        }
        if (uintBitsToFloat(cur.x) < uintBitsToFloat(back.x) && unpackUnorm4x8(back.y).a > (1.0 - _FLW_OIT_TRANSMIT_EPS)) {
            return;
        }
    }
    for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
        if (i < count && uintBitsToFloat(cur.x) > uintBitsToFloat(s[i].x)) {
            uvec2 t = s[i];
            s[i] = cur;
            cur = t;
        }
    }
    if (count >= _flw_mlabK) {
        for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
            if (i + 1u == count) {
                vec4 l = unpackUnorm4x8(s[i].y);
                vec4 c = unpackUnorm4x8(cur.y);
                vec4 merged = vec4(l.rgb + (1.0 - l.a) * c.rgb, 1.0 - (1.0 - l.a) * (1.0 - c.a));
                s[i] = uvec2(uintBitsToFloat(s[i].x) > uintBitsToFloat(cur.x) ? s[i].x : cur.x, packUnorm4x8(merged));
            }
        }
        for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
            if (i < count) {
                _flw_mlabData[_flw_mlabSlot(p, i)] = s[i];
            }
        }
    } else {
        for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
            if (i < count) {
                _flw_mlabData[_flw_mlabSlot(p, i)] = s[i];
            } else if (i == count) {
                _flw_mlabData[_flw_mlabSlot(p, i)] = cur;
            }
        }
        _flw_mlabCount[p] = count + 1u;
    }
}

void _flw_mlabInsertPrepared(uint p, uvec2 cur) {
    uint count = min(_flw_mlabCount[p], _flw_mlabK);
    if (_flw_mlabK <= _FLW_MLAB_WINDOW) {
        _flw_mlabInsertWindow(p, count, cur);
    } else {
        _flw_mlabInsertSerial(p, count, cur);
    }
}
#define _flw_mlabInsertPremul(rgb, a, z) { \
    uint _flw_mlabP = _flw_mlabPixelIndex(); \
    uvec2 _flw_mlabCur = uvec2(floatBitsToUint(z), packUnorm4x8(vec4(rgb, a))); \
    beginInvocationInterlockARB(); \
    _flw_mlabInsertPrepared(_flw_mlabP, _flw_mlabCur); \
    endInvocationInterlockARB(); \
}
#define _flw_mlabInsert(color, z) { \
    vec4 _flw_mlabC = (color); \
    uint _flw_mlabP = _flw_mlabPixelIndex(); \
    uvec2 _flw_mlabCur = uvec2(floatBitsToUint(z), packUnorm4x8(vec4(_flw_mlabC.rgb * _flw_mlabC.a, _flw_mlabC.a))); \
    beginInvocationInterlockARB(); \
    _flw_mlabInsertPrepared(_flw_mlabP, _flw_mlabCur); \
    endInvocationInterlockARB(); \
}
#endif

#endif

#ifdef _FLW_OIT_ABUFFER

#define _FLW_ABUF_NULL 0xFFFFFFFFu

#ifdef _FLW_MLAB_PRODUCER
layout(std430, binding = 24) restrict buffer _FlwAbufHead { uint _flw_abufHead[]; };
layout(std430, binding = 25) restrict buffer _FlwAbufNodes { uvec4 _flw_abufNodes[]; };
layout(std430, binding = 27) restrict buffer _FlwAbufCounter { uint _flw_abufCounter; };
#else
layout(std430, binding = 24) readonly restrict buffer _FlwAbufHead { uint _flw_abufHead[]; };
layout(std430, binding = 25) readonly restrict buffer _FlwAbufNodes { uvec4 _flw_abufNodes[]; };
layout(std430, binding = 27) readonly restrict buffer _FlwAbufCounter { uint _flw_abufCounter; };
#endif

#ifdef _FLW_MLAB_PRODUCER
layout(early_fragment_tests) in;

void _flw_mlabInsertPremul(vec3 rgb, float a, float deviceZ) {
    uint node = atomicAdd(_flw_abufCounter, 1u);
    if (node >= _flw_mlabMaxNodes) {
        return;
    }
    uint p = _flw_mlabPixelIndex();
    uint prevHead = atomicExchange(_flw_abufHead[p], node);
    _flw_abufNodes[node] = uvec4(floatBitsToUint(deviceZ), packUnorm4x8(vec4(rgb, a)), prevHead, 0u);
}
void _flw_mlabInsert(vec4 color, float deviceZ) { _flw_mlabInsertPremul(color.rgb * color.a, color.a, deviceZ); }
#endif

#endif
