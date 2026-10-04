layout(std430, binding = 0) coherent buffer FlwHeads { uint flw_heads[]; };
layout(std430, binding = 1) coherent buffer FlwNodes { uvec4 flw_nodes[]; };
layout(std430, binding = 2) coherent buffer FlwCounts { uint flw_count; uint flw_overflow; uint flw_maxLayers; uint flw_pad; };
uniform bool flw_oitActive;
// Cloud transmission is nonnegative; its sign bit carries the model's unlit flag.
const uint FLW_LAYER_UNLIT = 0x80000000u;

uint flw_reserveSlots(uint slots) { return atomicAdd(flw_count, slots); }

bool flw_layerBefore(uint left, uint right) {
    uvec4 a = flw_nodes[left];
    uvec4 b = flw_nodes[right];
    if (a.y != b.y) return a.y < b.y;
    if (a.z != b.z) return a.z < b.z;
    if (a.w != b.w) return a.w < b.w;
    a = flw_nodes[left + 1u];
    b = flw_nodes[right + 1u];
    if (a.x != b.x) return a.x < b.x;
    if (a.y != b.y) return a.y < b.y;
    if (a.z != b.z) return a.z < b.z;
    return (a.w & FLW_LAYER_UNLIT) <= (b.w & FLW_LAYER_UNLIT);
}
