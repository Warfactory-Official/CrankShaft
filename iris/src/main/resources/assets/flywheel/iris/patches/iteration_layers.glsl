layout(std430, binding = 1) buffer FlwHeads { uint flw_heads[]; };
layout(std430, binding = 2) coherent buffer FlwNodes { uvec4 flw_nodes[]; };
_FLW_CAPTURE_COUNTS_3
uniform bool flw_oitActive;
// Head bit of a list whose every value has alpha 0: its sum is order-free, so the sort leaves it unsorted.
const uint FLW_LIGHT_UNSORTED = 0x80000000u;
const bool flw_demandActive = false;

uint flw_lightDemandOffset(uint pixel, uvec2 size) {
    return size.x * size.y * 6u + pixel;
}

bool flw_lightDemanded(ivec2 pixel, uvec2 size) {
    return !flw_demandActive || (flw_heads[flw_lightDemandOffset(uint(pixel.y) * size.x + uint(pixel.x), size)] & 0x80000001u) != 0u;
}

bool flw_ownLightSplit(ivec2 pixel, uvec2 size) {
    return flw_demandActive
            ? (flw_heads[flw_lightDemandOffset(uint(pixel.y) * size.x + uint(pixel.x), size)] & 0x80000002u) != 0u
            : flw_needsLight != 0u;
}

// The head plane is followed by RGB sum and inclusive depth-bound planes for the additive-only lists.
uint flw_lightSummaryOffset(uint pixel, uvec2 size) {
    return size.x * size.y + pixel;
}

vec3 flw_lightSummaryRadiance(uint offset, uint stride) {
    return uintBitsToFloat(uvec3(flw_heads[offset], flw_heads[offset + stride],
            flw_heads[offset + 2u * stride]));
}

// Stops at 2^31, above any pool, so callers reject it; a 2^32 wrap would reissue linked nodes into cyclic lists.
uint flw_reserveSlots(uint slots) {
    return flw_count >= 0x80000000u ? 0x80000000u : atomicAdd(flw_count, slots);
}

// Farthest first.
bool flw_layerKeyBefore(uvec4 a, uvec4 b) {
    if (_FLW_REVERSED_DEPTH && uintBitsToFloat(a.y) != uintBitsToFloat(b.y))
        return uintBitsToFloat(a.y) < uintBitsToFloat(b.y);
    if (!_FLW_REVERSED_DEPTH && a.y != b.y) return a.y > b.y;
    if (a.z != b.z) return a.z > b.z;
    return a.w >= b.w;
}

bool flw_layerBefore(uint left, uint right) {
    return flw_layerKeyBefore(flw_nodes[left], flw_nodes[right]);
}

_FLW_LIGHT_SIMPLE

void flw_captureLight(ivec2 pixel, float depth, vec4 radiance, uvec2 size, bool late) {
    if (!flw_oitActive || all(equal(radiance, vec4(0.0)))) return;
    if (late && !flw_lightDemanded(pixel, size)) return;
    if (radiance.a != 0.0 && flw_needsLight == 0u) flw_needsLight = 1u;
    uint node = flw_reserveSlots(1u);
    if (node >= uint(flw_nodes.length())) { if (flw_overflow == 0u) flw_overflow = 1u; return; }
    uint previous = atomicExchange(flw_heads[uint(pixel.y) * size.x + uint(pixel.x)], node);
    flw_nodes[node] = uvec4(previous, floatBitsToUint(depth), packHalf2x16(radiance.rg), packHalf2x16(radiance.ba));
}

