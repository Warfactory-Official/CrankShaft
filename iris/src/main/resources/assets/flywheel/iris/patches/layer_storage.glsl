layout(std430, binding = 0) coherent buffer FlwHeads { uint flw_heads[]; };
layout(std430, binding = 1) coherent buffer FlwNodes { uvec4 flw_nodes[]; };
_FLW_CAPTURE_COUNTS_2
uniform bool flw_oitActive;
// Cloud transmission is nonnegative; its sign bit carries the model's unlit flag.
const uint FLW_LAYER_UNLIT = 0x80000000u;

// Stops at 2^31, above any pool, so callers reject it; a 2^32 wrap would reissue linked nodes into cyclic lists.
uint flw_reserveSlots(uint slots) { return flw_count >= 0x80000000u ? 0x80000000u : atomicAdd(flw_count, slots); }

// Nearer first. Raw reversed pool depth past the far plane is negative, so its order is float order, not bit order.
bool flw_layerBefore(uint left, uint right) {
    uvec4 a = flw_nodes[left];
    uvec4 b = flw_nodes[right];
    if (_FLW_REVERSED_DEPTH && uintBitsToFloat(a.y) != uintBitsToFloat(b.y))
        return uintBitsToFloat(a.y) > uintBitsToFloat(b.y);
    if (!_FLW_REVERSED_DEPTH && a.y != b.y) return a.y < b.y;
    if (a.z != b.z) return a.z < b.z;
    if (a.w != b.w) return a.w < b.w;
    a = flw_nodes[left + 1u];
    b = flw_nodes[right + 1u];
    if (a.x != b.x) return a.x < b.x;
    if (a.y != b.y) return a.y < b.y;
    if (a.z != b.z) return a.z < b.z;
    return (a.w & FLW_LAYER_UNLIT) <= (b.w & FLW_LAYER_UNLIT);
}
