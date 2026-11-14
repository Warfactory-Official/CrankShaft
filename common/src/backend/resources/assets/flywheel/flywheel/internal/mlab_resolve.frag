layout(location = 0) out vec4 frag;

// Alpha 1: premultiplied blend => exact replace.
layout(location = 1) out vec4 _flw_nearestOut;
#define _FLW_TRACK_NODE(c, depth) if (!occluded && (c).a > 0.0) { occluded = true; nearestDepth = (depth); }
#define _FLW_RESOLVE_OUT(acc, transmittance, occluded, nearestDepth) { frag = vec4((acc) + emission, 1.0 - (transmittance)); \
    _flw_nearestOut = vec4((occluded) ? (nearestDepth) : - 1.0, 0.0, 0.0, 1.0); }

#ifdef _FLW_OIT_EMISSION
uniform sampler2D _flw_emission;
#endif

void _flw_layerInsertW(inout uvec2 s[_FLW_MLAB_WINDOW], inout uint count, uvec2 cur) {
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
    } else {
        for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
            if (i == count) {
                s[i] = cur;
            }
        }
        count += 1u;
    }
}

void _flw_layerInsertM(inout uvec2 s[_FLW_MLAB_MAX], inout uint count, uint cap, uvec2 cur) {
    for (uint i = 0u; i < count; ++i) {
        if (uintBitsToFloat(cur.x) > uintBitsToFloat(s[i].x)) {
            uvec2 t = s[i];
            s[i] = cur;
            cur = t;
        }
    }
    if (count >= cap) {
        vec4 l = unpackUnorm4x8(s[count - 1u].y);
        vec4 c = unpackUnorm4x8(cur.y);
        vec4 merged = vec4(l.rgb + (1.0 - l.a) * c.rgb, 1.0 - (1.0 - l.a) * (1.0 - c.a));
        s[count - 1u] = uvec2(uintBitsToFloat(s[count - 1u].x) > uintBitsToFloat(cur.x) ? s[count - 1u].x : cur.x, packUnorm4x8(merged));
    } else {
        s[count] = cur;
        count += 1u;
    }
}

#ifdef _FLW_OIT_MLAB
void _flw_mlabTrackNearest(uvec2 node, inout bool occluded, inout float nearestDepth) {
    if ((node.y >> 24u) != 0u) {
        float depth = uintBitsToFloat(node.x);
        nearestDepth = occluded ? max(nearestDepth, depth) : depth;
        occluded = true;
    }
}

void _flw_mlabPrepareLayer(inout bool present, uvec2 node, inout bool hasOpaque, inout uvec2 opaque,
        inout bool occluded, inout float nearestDepth) {
    if (!present) { return; }
    _flw_mlabTrackNearest(node, occluded, nearestDepth);
    if ((node.y >> 24u) == 255u) {
        if (!hasOpaque || uintBitsToFloat(node.x) > uintBitsToFloat(opaque.x)) { opaque = node; }
        hasOpaque = true;
        present = false;
    }
}
#endif

void main() {
    uint p = _flw_mlabPixelIndex();
    ivec2 px = ivec2(gl_FragCoord.xy);
    #ifdef _FLW_OIT_EMISSION
    vec3 emission = texelFetch(_flw_emission, px, 0).rgb;
    #else
    const vec3 emission = vec3(0.0);
    #endif

    bool hasL0 = false;
    bool hasL1 = false;
    bool hasL2 = false;
    uvec2 l0 = uvec2(0u);
    uvec2 l1 = uvec2(0u);
    uvec2 l2 = uvec2(0u);
    if ((_flw_mlabLayerMask & 1u) != 0u) {
        vec4 c = texelFetch(_flw_layerColor, px, 0);
        if (c.a >= 0.001) {
            hasL0 = true;
            l0 = uvec2(floatBitsToUint(max(texelFetch(_flw_layerDepth, px, 0).r, 1e-9)), packUnorm4x8(c));
        }
    }
    if ((_flw_mlabLayerMask & 2u) != 0u) {
        vec4 c = texelFetch(_flw_layerColor1, px, 0);
        if (c.a >= 0.001) {
            hasL1 = true;
            l1 = uvec2(floatBitsToUint(max(texelFetch(_flw_layerDepth1, px, 0).r, 1e-9)), packUnorm4x8(c));
        }
    }
    if ((_flw_mlabLayerMask & 4u) != 0u) {
        vec4 c = texelFetch(_flw_layerColor2, px, 0);
        if (c.a >= 0.001) {
            hasL2 = true;
            l2 = uvec2(floatBitsToUint(max(texelFetch(_flw_layerDepth2, px, 0).r, 1e-9)), packUnorm4x8(c));
        }
    }
    bool hasL3 = false;
    uvec2 l3 = uvec2(0u);
    if ((_flw_mlabLayerMask & 8u) != 0u) {
        vec4 c = texelFetch(_flw_layerColor3, px, 0);
        if (c.a >= 0.001) {
            hasL3 = true;
            l3 = uvec2(floatBitsToUint(max(texelFetch(_flw_layerDepth3, px, 0).r, 1e-9)), packUnorm4x8(c));
        }
    }
    bool anyLayer = hasL0 || hasL1 || hasL2 || hasL3;

    #ifdef _FLW_OIT_KBUFFER
    uint count = min(_flw_mlabCount[p], _flw_mlabK);
    if (count == 0u && !anyLayer && emission == vec3(0.0)) {
        discard;
    }
    uint base = p * _flw_mlabK;
    if (_flw_mlabK <= _FLW_MLAB_WINDOW) {
        uvec2 s[_FLW_MLAB_WINDOW];
        for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
            s[i] = i < count ? _flw_mlabData[base + i] : uvec2(0xFF800000u, 0u);
        }
        #define _FLW_KB_CE(i, j) { if (uintBitsToFloat(s[j].x) > uintBitsToFloat(s[i].x)) { uvec2 t = s[i]; s[i] = s[j]; s[j] = t; } }
        if (_flw_mlabK <= 4u) {
            _FLW_KB_CE(0, 1) _FLW_KB_CE(2, 3)
_FLW_KB_CE(0, 2) _FLW_KB_CE(1, 3)
_FLW_KB_CE(1, 2)
} else if (_flw_mlabK <= 8u) {
    _FLW_KB_CE(0, 1) _FLW_KB_CE(2, 3) _FLW_KB_CE(4, 5) _FLW_KB_CE(6, 7)
_FLW_KB_CE(0, 2) _FLW_KB_CE(1, 3) _FLW_KB_CE(4, 6) _FLW_KB_CE(5, 7)
_FLW_KB_CE(1, 2) _FLW_KB_CE(5, 6)
_FLW_KB_CE(0, 4) _FLW_KB_CE(1, 5) _FLW_KB_CE(2, 6) _FLW_KB_CE(3, 7)
_FLW_KB_CE(2, 4) _FLW_KB_CE(3, 5)
_FLW_KB_CE(1, 2) _FLW_KB_CE(3, 4) _FLW_KB_CE(5, 6)
} else {
for (uint sweep = 0u; sweep + 1u < _FLW_MLAB_WINDOW; ++sweep) {
    for (uint j = 0u; j + 1u < _FLW_MLAB_WINDOW; ++j) {
        if (j + 1u < count && uintBitsToFloat(s[j + 1u].x) > uintBitsToFloat(s[j].x)) {
            uvec2 t = s[j]; s[j] = s[j + 1u]; s[j + 1u] = t;
        }
    }
}
}
#undef _FLW_KB_CE
if (hasL0) { _flw_layerInsertW(s, count, l0); }
if (hasL1) { _flw_layerInsertW(s, count, l1); }
if (hasL2) { _flw_layerInsertW(s, count, l2); }
if (hasL3) { _flw_layerInsertW(s, count, l3); }
vec3 acc = vec3(0.0);
float transmittance = 1.0;
bool occluded = false;
float nearestDepth = 0.0;
for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
    if (i < count) {
        vec4 c = unpackUnorm4x8(s[i].y);
        _FLW_TRACK_NODE(c, uintBitsToFloat(s[i].x))
acc += transmittance * c.rgb;
transmittance *= 1.0 - c.a;
}
}
_FLW_RESOLVE_OUT(acc, transmittance, occluded, nearestDepth)
} else {
uvec2 s[_FLW_MLAB_MAX];
for (uint i = 0u; i < count; ++i) {
    s[i] = _flw_mlabData[base + i];
}
for (uint i = 0u; i + 1u < count; ++i) {
    uint best = i;
    for (uint j = i + 1u; j < count; ++j) {
        if (uintBitsToFloat(s[j].x) > uintBitsToFloat(s[best].x)) {
            best = j;
        }
    }
    uvec2 t = s[i]; s[i] = s[best]; s[best] = t;
}
if (hasL0) { _flw_layerInsertM(s, count, _flw_mlabK, l0); }
if (hasL1) { _flw_layerInsertM(s, count, _flw_mlabK, l1); }
if (hasL2) { _flw_layerInsertM(s, count, _flw_mlabK, l2); }
if (hasL3) { _flw_layerInsertM(s, count, _flw_mlabK, l3); }
vec3 acc = vec3(0.0);
float transmittance = 1.0;
bool occluded = false;
float nearestDepth = 0.0;
for (uint i = 0u; i < count; ++i) {
    vec4 c = unpackUnorm4x8(s[i].y);
    _FLW_TRACK_NODE(c, uintBitsToFloat(s[i].x))
acc += transmittance * c.rgb;
transmittance *= 1.0 - c.a;
}
_FLW_RESOLVE_OUT(acc, transmittance, occluded, nearestDepth)
}
#endif

#ifdef _FLW_OIT_MLAB
uint state = _flw_mlabCount[p];
uint count = min(state & _FLW_MLAB_COUNT_MASK, _flw_mlabK);
bool hasOpaque = (state & _FLW_MLAB_OPAQUE) != 0u;
uvec2 opaque = hasOpaque ? _flw_mlabData[_flw_mlabOpaqueSlot(p)] : uvec2(0u);
bool occluded = false;
float nearestDepth = 0.0;
_flw_mlabPrepareLayer(hasL0, l0, hasOpaque, opaque, occluded, nearestDepth);
_flw_mlabPrepareLayer(hasL1, l1, hasOpaque, opaque, occluded, nearestDepth);
_flw_mlabPrepareLayer(hasL2, l2, hasOpaque, opaque, occluded, nearestDepth);
_flw_mlabPrepareLayer(hasL3, l3, hasOpaque, opaque, occluded, nearestDepth);
float opaqueDepth = hasOpaque ? uintBitsToFloat(opaque.x) : -1.0;
hasL0 = hasL0 && uintBitsToFloat(l0.x) > opaqueDepth;
hasL1 = hasL1 && uintBitsToFloat(l1.x) > opaqueDepth;
hasL2 = hasL2 && uintBitsToFloat(l2.x) > opaqueDepth;
hasL3 = hasL3 && uintBitsToFloat(l3.x) > opaqueDepth;
if (count == 0u && !hasOpaque && !anyLayer && emission == vec3(0.0)) {
    discard;
}
vec3 acc = vec3(0.0);
float transmittance = 1.0;
if (_flw_mlabK <= _FLW_MLAB_WINDOW) {
    uvec2 s[_FLW_MLAB_WINDOW];
    for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
        if (i < count) {
            s[i] = _flw_mlabData[_flw_mlabSlot(p, i)];
            _flw_mlabTrackNearest(s[i], occluded, nearestDepth);
        }
    }
    if (hasL0) { _flw_layerInsertW(s, count, l0); }
    if (hasL1) { _flw_layerInsertW(s, count, l1); }
    if (hasL2) { _flw_layerInsertW(s, count, l2); }
    if (hasL3) { _flw_layerInsertW(s, count, l3); }
    for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
        if (i < count) {
            if (uintBitsToFloat(s[i].x) < opaqueDepth) { break; }
            vec4 c = unpackUnorm4x8(s[i].y);
            acc += transmittance * c.rgb;
            transmittance *= 1.0 - c.a;
        }
    }
} else {
    uvec2 s[_FLW_MLAB_MAX];
    for (uint i = 0u; i < count; ++i) {
        s[i] = _flw_mlabData[_flw_mlabSlot(p, i)];
        _flw_mlabTrackNearest(s[i], occluded, nearestDepth);
    }
    if (hasL0) { _flw_layerInsertM(s, count, _flw_mlabK, l0); }
    if (hasL1) { _flw_layerInsertM(s, count, _flw_mlabK, l1); }
    if (hasL2) { _flw_layerInsertM(s, count, _flw_mlabK, l2); }
    if (hasL3) { _flw_layerInsertM(s, count, _flw_mlabK, l3); }
    for (uint i = 0u; i < count; ++i) {
        if (uintBitsToFloat(s[i].x) < opaqueDepth) { break; }
        vec4 c = unpackUnorm4x8(s[i].y);
        acc += transmittance * c.rgb;
        transmittance *= 1.0 - c.a;
    }
}
if (hasOpaque) {
    vec4 c = unpackUnorm4x8(opaque.y);
    _flw_mlabTrackNearest(opaque, occluded, nearestDepth);
    acc += transmittance * c.rgb;
    transmittance = 0.0;
}
    _FLW_RESOLVE_OUT(acc, transmittance, occluded, nearestDepth)
#endif

#ifdef _FLW_OIT_ABUFFER
uint head = _flw_abufHead[p];
if (head == 0xFFFFFFFFu && !anyLayer && emission == vec3(0.0)) {
    discard;
}
uint cap = min(_flw_mlabK, uint(_FLW_MLAB_MAX));
vec3 acc = vec3(0.0);
float transmittance = 1.0;
bool occluded = false;
float nearestDepth = 0.0;
if (_flw_mlabK <= _FLW_MLAB_WINDOW) {
    uvec2 s[_FLW_MLAB_WINDOW];
    uint n = 0u;
    uint node = head;
    while (node != 0xFFFFFFFFu) {
        uvec4 rec = _flw_abufNodes[node];
        uvec2 samp = uvec2(rec.x, rec.y);
        for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
            if (i < n && uintBitsToFloat(samp.x) > uintBitsToFloat(s[i].x)) {
                uvec2 t = s[i]; s[i] = samp; samp = t;
            }
        }
        if (n < cap) {
            for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
                if (i == n) { s[i] = samp; }
            }
            ++n;
        }
        node = rec.z;
    }
    if (hasL0) { _flw_layerInsertW(s, n, l0); }
    if (hasL1) { _flw_layerInsertW(s, n, l1); }
    if (hasL2) { _flw_layerInsertW(s, n, l2); }
    if (hasL3) { _flw_layerInsertW(s, n, l3); }
    for (uint i = 0u; i < _FLW_MLAB_WINDOW; ++i) {
        if (i < n) {
            vec4 c = unpackUnorm4x8(s[i].y);
            _FLW_TRACK_NODE(c, uintBitsToFloat(s[i].x))
acc += transmittance * c.rgb;
transmittance *= 1.0 - c.a;
}
}
} else {
uvec2 s[_FLW_MLAB_MAX];
uint n = 0u;
uint node = head;
while (node != 0xFFFFFFFFu) {
    uvec4 rec = _flw_abufNodes[node];
    uvec2 samp = uvec2(rec.x, rec.y);
    if (n < cap) {
        uint i = n;
        while (i > 0u && uintBitsToFloat(s[i - 1u].x) < uintBitsToFloat(samp.x)) {
            s[i] = s[i - 1u];
            --i;
        }
        s[i] = samp;
        ++n;
    } else if (uintBitsToFloat(samp.x) > uintBitsToFloat(s[n - 1u].x)) {
        uint i = n - 1u;
        while (i > 0u && uintBitsToFloat(s[i - 1u].x) < uintBitsToFloat(samp.x)) {
            s[i] = s[i - 1u];
            --i;
        }
        s[i] = samp;
    }
    node = rec.z;
}
if (hasL0) { _flw_layerInsertM(s, n, cap, l0); }
if (hasL1) { _flw_layerInsertM(s, n, cap, l1); }
if (hasL2) { _flw_layerInsertM(s, n, cap, l2); }
if (hasL3) { _flw_layerInsertM(s, n, cap, l3); }
for (uint i = 0u; i < n; ++i) {
    vec4 c = unpackUnorm4x8(s[i].y);
    _FLW_TRACK_NODE(c, uintBitsToFloat(s[i].x))
acc += transmittance * c.rgb;
transmittance *= 1.0 - c.a;
}
}
_FLW_RESOLVE_OUT(acc, transmittance, occluded, nearestDepth)
#endif
}
