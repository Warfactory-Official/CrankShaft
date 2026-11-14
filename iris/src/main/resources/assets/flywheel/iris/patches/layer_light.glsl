layout(std430, binding = 5) buffer FlwLightHeads { uint flw_lightHeads[]; };
// Head bit of a list whose every value has alpha 0: its sum is order-free, so the sort leaves it unsorted.
const uint FLW_LIGHT_UNSORTED = 0x80000000u;
const bool flw_demandActive = false;

uint flw_lightDemandOffset(uint pixel, uvec2 size) {
    return size.x * size.y * 6u + pixel;
}

bool flw_lightDemanded(ivec2 pixel, uvec2 size) {
    return !flw_demandActive || (flw_lightHeads[flw_lightDemandOffset(uint(pixel.y) * size.x + uint(pixel.x), size)] & 0x80000001u) != 0u;
}

bool flw_ownLightSplit(ivec2 pixel, uvec2 size) {
    return flw_demandActive
            ? (flw_lightHeads[flw_lightDemandOffset(uint(pixel.y) * size.x + uint(pixel.x), size)] & 0x80000002u) != 0u
            : flw_needsLight != 0u;
}

// The head plane is followed by RGB sum and inclusive depth-bound planes for the additive-only lists.
uint flw_lightSummaryOffset(uint pixel, uvec2 size) {
    return size.x * size.y + pixel;
}

vec3 flw_lightSummaryRadiance(uint offset, uint stride) {
    return uintBitsToFloat(uvec3(flw_lightHeads[offset], flw_lightHeads[offset + stride],
            flw_lightHeads[offset + 2u * stride]));
}

bool flw_lightBefore(uint left, uint right) {
    uvec4 a = flw_nodes[left], b = flw_nodes[right];
    if (_FLW_REVERSED_DEPTH && uintBitsToFloat(a.y) != uintBitsToFloat(b.y))
        return uintBitsToFloat(a.y) > uintBitsToFloat(b.y);
    if (!_FLW_REVERSED_DEPTH && a.y != b.y) return a.y < b.y;
    if (a.z != b.z) return a.z < b.z;
    return a.w <= b.w;
}

_FLW_LIGHT_SIMPLE

// Native light can precede material capture; only the late guest pass can omit material-free records.
void flw_captureLight(ivec2 pixel, float depth, vec4 value, uvec2 size, bool late) {
    if (!flw_oitActive || all(equal(value, vec4(0.0)))) return;
    if (late && !flw_lightDemanded(pixel, size)) return;
    if (value.a != 0.0 && flw_needsLight == 0u) flw_needsLight = 1u;
    uint node = flw_reserveSlots(1u);
    // The reservation still counts the pool's demand; one store raises the flag instead of an atomic per fragment.
    if (node >= uint(flw_nodes.length())) { if (flw_overflow == 0u) flw_overflow = 1u; return; }
    uint previous = atomicExchange(flw_lightHeads[uint(pixel.y) * size.x + uint(pixel.x)], node);
    flw_nodes[node] = uvec4(previous, floatBitsToUint(depth), packHalf2x16(value.rg), packHalf2x16(value.ba));
}

vec3 flw_resolveLight(vec3 background, ivec2 pixel, float front, float back, uvec2 size) {
    if (!flw_oitActive || flw_overflow != 0u || any(greaterThanEqual(uvec2(pixel), size))) return background;
    if (flw_lightSimpleInitialized(pixel, size)) return flw_resolveSimpleLight(background, pixel, front, back, size);
    uint node = flw_lightHeads[uint(pixel.y) * size.x + uint(pixel.x)];
    vec3 light = vec3(0.0);
    if (node != 0xffffffffu && (node & FLW_LIGHT_UNSORTED) != 0u) {
        uint summary = flw_lightSummaryOffset(uint(pixel.y) * size.x + uint(pixel.x), size);
        uint stride = size.x * size.y;
        float nearest = uintBitsToFloat(flw_lightHeads[summary + 3u * stride]);
        float farthest = uintBitsToFloat(flw_lightHeads[summary + 4u * stride]);
        if (_FLW_REVERSED_DEPTH ? farthest > front || nearest <= back : farthest < front || nearest >= back)
            return background;
        if (_FLW_REVERSED_DEPTH ? nearest <= front && farthest > back : nearest >= front && farthest < back)
            return flw_lightSummaryRadiance(summary, stride) + background;
        for (node &= ~FLW_LIGHT_UNSORTED; node != 0xffffffffu; node = flw_nodes[node].x) {
            uvec4 entry = flw_nodes[node];
            float depth = uintBitsToFloat(entry.y);
            if (_FLW_REVERSED_DEPTH ? depth <= front && depth > back : depth >= front && depth < back)
                light += vec3(unpackHalf2x16(entry.z), unpackHalf2x16(entry.w).x);
        }
        return light + background;
    }
    float transmission = 1.0;
    while (node != 0xffffffffu) {
        uvec4 entry = flw_nodes[node];
        float depth = uintBitsToFloat(entry.y);
        if (_FLW_REVERSED_DEPTH ? depth <= back : depth >= back) break;
        if (_FLW_REVERSED_DEPTH ? depth <= front : depth >= front) {
            vec4 value = vec4(unpackHalf2x16(entry.z), unpackHalf2x16(entry.w));
            light += transmission * value.rgb;
            transmission *= 1.0 - value.a;
        }
        node = entry.x;
    }
    return light + transmission * background;
}
