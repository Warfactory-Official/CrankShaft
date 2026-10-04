layout(std430, binding = 5) coherent buffer FlwLightHeads { uint flw_lightHeads[]; };

bool flw_lightBefore(uint left, uint right) {
    uvec4 a = flw_nodes[left], b = flw_nodes[right];
    if (a.y != b.y) return a.y < b.y;
    if (a.z != b.z) return a.z < b.z;
    return a.w <= b.w;
}

void flw_captureLight(ivec2 pixel, float depth, vec4 value, uint width) {
    if (!flw_oitActive || all(equal(value, vec4(0.0)))) return;
    uint node = flw_reserveSlots(1u);
    if (node >= uint(flw_nodes.length())) { atomicOr(flw_overflow, 1u); return; }
    uint previous = atomicExchange(flw_lightHeads[uint(pixel.y) * width + uint(pixel.x)], node);
    flw_nodes[node] = uvec4(previous, floatBitsToUint(depth), packHalf2x16(value.rg), packHalf2x16(value.ba));
}

vec3 flw_resolveLight(vec3 background, ivec2 pixel, float front, float back, uint width) {
    if (!flw_oitActive || flw_overflow != 0u) return background;
    uint node = flw_lightHeads[uint(pixel.y) * width + uint(pixel.x)];
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
