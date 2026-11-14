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

// Exact: a sample packing to 0 adds no colour and keeps transmittance 1, so producers drop it before it takes a slot.

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
    uint encoded = packUnorm4x8(vec4(rgb, a));
    if (encoded == 0u) {
        return;
    }
    uint p = _flw_mlabPixelIndex();
    uint slot = atomicAdd(_flw_mlabCount[p], 1u);
    if (slot < _flw_mlabK) {
        _flw_mlabData[p * _flw_mlabK + slot] = uvec2(floatBitsToUint(deviceZ), encoded);
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

const uint _FLW_MLAB_OPAQUE = 0x80000000u;
const uint _FLW_MLAB_COUNT_MASK = ~_FLW_MLAB_OPAQUE;

uint _flw_mlabOpaqueSlot(uint p) { return _flw_mlabSlot(p, _flw_mlabK); }

#ifdef _FLW_MLAB_PRODUCER
layout(early_fragment_tests) in;
#ifndef _FLW_MLAB_LAYOUT_DECLARED
layout(pixel_interlock_unordered) in;
#endif

void _flw_mlabSetCount(uint p, uint count) {
    _flw_mlabCount[p] = (_flw_mlabCount[p] & _FLW_MLAB_OPAQUE) | count;
}

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
        _flw_mlabSetCount(p, count + 1u);
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
    // Write back from the first changed slot only.
    uint lo = count;
    for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
        if (i < count && uintBitsToFloat(cur.x) > uintBitsToFloat(s[i].x)) {
            lo = min(lo, i);
            uvec2 t = s[i];
            s[i] = cur;
            cur = t;
        }
    }
    if (count >= _flw_mlabK) {
        lo = min(lo, count - 1u);
        for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
            if (i + 1u == count) {
                vec4 l = unpackUnorm4x8(s[i].y);
                vec4 c = unpackUnorm4x8(cur.y);
                vec4 merged = vec4(l.rgb + (1.0 - l.a) * c.rgb, 1.0 - (1.0 - l.a) * (1.0 - c.a));
                s[i] = uvec2(uintBitsToFloat(s[i].x) > uintBitsToFloat(cur.x) ? s[i].x : cur.x, packUnorm4x8(merged));
            }
        }
        for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
            if (i >= lo && i < count) {
                _flw_mlabData[_flw_mlabSlot(p, i)] = s[i];
            }
        }
    } else {
        for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
            if (i >= lo && i < count) {
                _flw_mlabData[_flw_mlabSlot(p, i)] = s[i];
            } else if (i == count) {
                _flw_mlabData[_flw_mlabSlot(p, i)] = cur;
            }
        }
        _flw_mlabSetCount(p, count + 1u);
    }
}

void _flw_mlabInsertPrepared(uint p, uvec2 cur) {
    uint state = _flw_mlabCount[p];
    bool hasOpaque = (state & _FLW_MLAB_OPAQUE) != 0u;
    uint opaqueSlot = _flw_mlabOpaqueSlot(p);
    if ((cur.y >> 24u) == 255u) {
        // An opaque surface keeps its actual depth: merging it with a nearer additive fragment would hide
        // an intervening surface arriving later, even though that additive fragment occludes nothing.
        if (!hasOpaque || uintBitsToFloat(cur.x) > uintBitsToFloat(_flw_mlabData[opaqueSlot].x)) {
            _flw_mlabData[opaqueSlot] = cur;
            _flw_mlabCount[p] = state | _FLW_MLAB_OPAQUE;
        }
        return;
    }
    if (hasOpaque && uintBitsToFloat(cur.x) < uintBitsToFloat(_flw_mlabData[opaqueSlot].x)) {
        return;
    }
    uint count = min(state & _FLW_MLAB_COUNT_MASK, _flw_mlabK);
    // A full list holds nothing farther than its last sample: a sample not nearer than it only merges there.
    if (count == _flw_mlabK) {
        uint last = _flw_mlabSlot(p, count - 1u);
        uvec2 lastS = _flw_mlabData[last];
        if (uintBitsToFloat(cur.x) <= uintBitsToFloat(lastS.x)) {
            vec4 l = unpackUnorm4x8(lastS.y);
            if (uintBitsToFloat(cur.x) < uintBitsToFloat(lastS.x) && l.a > (1.0 - _FLW_OIT_TRANSMIT_EPS)) {
                return;
            }
            vec4 c = unpackUnorm4x8(cur.y);
            _flw_mlabData[last] = uvec2(lastS.x, packUnorm4x8(vec4(l.rgb + (1.0 - l.a) * c.rgb,
                    1.0 - (1.0 - l.a) * (1.0 - c.a))));
            return;
        }
    }
    if (_flw_mlabK <= _FLW_MLAB_WINDOW) {
        _flw_mlabInsertWindow(p, count, cur);
    } else {
        _flw_mlabInsertSerial(p, count, cur);
    }
}
#define _flw_mlabInsertPremul(rgb, a, z) { \
    uint _flw_mlabP = _flw_mlabPixelIndex(); \
    uvec2 _flw_mlabCur = uvec2(floatBitsToUint(z), packUnorm4x8(vec4(rgb, a))); \
    if (_flw_mlabCur.y == 0u) discard; \
    beginInvocationInterlockARB(); \
    _flw_mlabInsertPrepared(_flw_mlabP, _flw_mlabCur); \
    endInvocationInterlockARB(); \
 }
#define _flw_mlabInsert(color, z) { \
    vec4 _flw_mlabC = (color); \
    uint _flw_mlabP = _flw_mlabPixelIndex(); \
    uvec2 _flw_mlabCur = uvec2(floatBitsToUint(z), packUnorm4x8(vec4(_flw_mlabC.rgb * _flw_mlabC.a, _flw_mlabC.a))); \
    if (_flw_mlabCur.y == 0u) discard; \
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
layout(std430, binding = 27) coherent restrict buffer _FlwAbufCounter { uint _flw_abufCounter; };
#else
layout(std430, binding = 24) readonly restrict buffer _FlwAbufHead { uint _flw_abufHead[]; };
layout(std430, binding = 25) readonly restrict buffer _FlwAbufNodes { uvec4 _flw_abufNodes[]; };
layout(std430, binding = 27) readonly restrict buffer _FlwAbufCounter { uint _flw_abufCounter; };
#endif

#ifdef _FLW_MLAB_PRODUCER
layout(early_fragment_tests) in;

void _flw_mlabInsertPremul(vec3 rgb, float a, float deviceZ) {
    uint encoded = packUnorm4x8(vec4(rgb, a));
    // A full pool stops bumping the allocator: past 2^32 bumps it would reissue linked nodes, and a cyclic list never
    // ends the resolve walk.
    if (encoded == 0u || _flw_abufCounter >= _flw_mlabMaxNodes) {
        return;
    }
    uint node = atomicAdd(_flw_abufCounter, 1u);
    if (node >= _flw_mlabMaxNodes) {
        return;
    }
    uint p = _flw_mlabPixelIndex();
    uint prevHead = atomicExchange(_flw_abufHead[p], node);
    _flw_abufNodes[node] = uvec4(floatBitsToUint(deviceZ), encoded, prevHead, 0u);
}
void _flw_mlabInsert(vec4 color, float deviceZ) { _flw_mlabInsertPremul(color.rgb * color.a, color.a, deviceZ); }
#endif

#endif

#ifndef _FLW_MLAB_PRODUCER
uniform sampler2D _flw_layerColor;   // clouds (mask bit 0)
uniform sampler2D _flw_layerDepth;
uniform sampler2D _flw_layerColor1;  // item-entity layer (mask bit 1)
uniform sampler2D _flw_layerDepth1;
uniform sampler2D _flw_layerColor2;  // particle layer (mask bit 2)
uniform sampler2D _flw_layerDepth2;
uniform sampler2D _flw_layerColor3;  // weather layer (mask bit 3)
uniform sampler2D _flw_layerDepth3;
#endif

#ifdef _FLW_MLAB_EMISSION
layout(early_fragment_tests) in;

layout(location = 0) out vec4 _flw_mlabEmission;

float _flw_mlabLayerTransmittance(sampler2D color, sampler2D depth, ivec2 px, float deviceZ) {
    float a = texelFetch(color, px, 0).a;
    return a >= 0.001 && max(texelFetch(depth, px, 0).r, 1e-9) > deviceZ ? 1.0 - a : 1.0;
}

// ORDER_INDEPENDENT_ADDITIVE, drawn after every occluding insert: it occludes nothing, so the samples it reads are
// final, and it adds its colour times the transmittance of those in front of it.
void _flw_mlabEmit(vec3 premultiplied, float deviceZ) {
    if (max(max(premultiplied.r, premultiplied.g), premultiplied.b) <= 0.) {
        discard;
    }
    uint p = _flw_mlabPixelIndex();
    float t = 1.0;
    #ifdef _FLW_OIT_KBUFFER
    uint count = min(_flw_mlabCount[p], _flw_mlabK);
    for (uint i = 0u; i < count; ++i) {
        uvec2 s = _flw_mlabData[p * _flw_mlabK + i];
        if (uintBitsToFloat(s.x) > deviceZ) {
            t *= 1.0 - unpackUnorm4x8(s.y).a;
        }
    }
    #elif defined(_FLW_OIT_MLAB)
    uint state = _flw_mlabCount[p];
    if ((state & _FLW_MLAB_OPAQUE) != 0u && uintBitsToFloat(_flw_mlabData[_flw_mlabOpaqueSlot(p)].x) > deviceZ) {
        discard;
    }
    uint count = min(state & _FLW_MLAB_COUNT_MASK, _flw_mlabK);
    // Nearest first.
    for (uint i = 0u; i < count; ++i) {
        uvec2 s = _flw_mlabData[_flw_mlabSlot(p, i)];
        if (uintBitsToFloat(s.x) <= deviceZ) {
            break;
        }
        t *= 1.0 - unpackUnorm4x8(s.y).a;
    }
    #else
    // Every pool node in front, including any beyond the resolve's nearest-K cap.
    for (uint node = _flw_abufHead[p]; node != _FLW_ABUF_NULL;) {
        uvec4 rec = _flw_abufNodes[node];
        if (uintBitsToFloat(rec.x) > deviceZ) {
            t *= 1.0 - unpackUnorm4x8(rec.y).a;
        }
        node = rec.z;
    }
    #endif
    ivec2 px = ivec2(gl_FragCoord.xy);
    if ((_flw_mlabLayerMask & 1u) != 0u) {
        t *= _flw_mlabLayerTransmittance(_flw_layerColor, _flw_layerDepth, px, deviceZ);
    }
    if ((_flw_mlabLayerMask & 2u) != 0u) {
        t *= _flw_mlabLayerTransmittance(_flw_layerColor1, _flw_layerDepth1, px, deviceZ);
    }
    if ((_flw_mlabLayerMask & 4u) != 0u) {
        t *= _flw_mlabLayerTransmittance(_flw_layerColor2, _flw_layerDepth2, px, deviceZ);
    }
    if ((_flw_mlabLayerMask & 8u) != 0u) {
        t *= _flw_mlabLayerTransmittance(_flw_layerColor3, _flw_layerDepth3, px, deviceZ);
    }
    _flw_mlabEmission = vec4(premultiplied * t, 0.);
}
#endif
