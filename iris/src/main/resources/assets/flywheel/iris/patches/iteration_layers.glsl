layout(std430, binding = 1) coherent buffer FlwHeads { uint flw_heads[]; };
layout(std430, binding = 2) coherent buffer FlwNodes { uvec4 flw_nodes[]; };
layout(std430, binding = 3) coherent buffer FlwCounts { uint flw_count; uint flw_overflow; uint flw_maxLayers; uint flw_pad; };
layout(std430, binding = 4) coherent buffer FlwTiles { uint flw_tiles[]; };
uniform bool flw_oitActive;

uint flw_reserveSlots(uint slots) {
    return atomicAdd(flw_count, slots);
}

bool flw_layerBefore(uint left, uint right) {
    uvec4 a = flw_nodes[left];
    uvec4 b = flw_nodes[right];
    if (a.y != b.y) return a.y < b.y;
    if (a.z != b.z) return a.z < b.z;
    return a.w <= b.w;
}

void flw_captureLight(ivec2 pixel, float depth, vec4 radiance, uint width) {
    if (!flw_oitActive || all(equal(radiance, vec4(0.0)))) return;
    uint node = flw_reserveSlots(1u);
    if (node >= uint(flw_nodes.length())) { atomicOr(flw_overflow, 1u); return; }
    uint previous = atomicExchange(flw_heads[uint(pixel.y) * width + uint(pixel.x)], node);
    flw_nodes[node] = uvec4(previous, floatBitsToUint(depth), packHalf2x16(radiance.rg), packHalf2x16(radiance.ba));
}

vec3 flw_resolveLight(vec3 background, ivec2 pixel, float front, float back, uvec2 size) {
    if (!flw_oitActive || flw_overflow != 0u || any(greaterThanEqual(uvec2(pixel), size))) return background;
    uint node = flw_heads[uint(pixel.y) * size.x + uint(pixel.x)];
    vec3 light = vec3(0.0);
    float transmission = 1.0;
    while (node != 0xffffffffu) {
        uvec4 entry = flw_nodes[node];
        float depth = uintBitsToFloat(entry.y);
        if (depth >= back) break;
        if (depth >= front) {
            vec4 value = vec4(unpackHalf2x16(entry.z), unpackHalf2x16(entry.w));
            light += transmission * value.rgb;
            transmission *= 1.0 - value.a;
        }
        node = entry.x;
    }
    return light + transmission * background;
}
