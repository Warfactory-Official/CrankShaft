layout(std430, binding = 6) coherent buffer FlwMaterialHeads { uint flw_materialHeads[]; };

// Nearer first. Raw reversed pool depth past the far plane is negative, so its order is float order, not bit order.
bool flw_materialBefore(uint left, uint right) {
    if (_FLW_REVERSED_DEPTH && uintBitsToFloat(flw_nodes[left].y) != uintBitsToFloat(flw_nodes[right].y))
        return uintBitsToFloat(flw_nodes[left].y) > uintBitsToFloat(flw_nodes[right].y);
    for (uint i = 0u; i < 3u; i++) {
        uvec4 a = flw_nodes[left + i], b = flw_nodes[right + i];
        for (uint j = i == 0u?1u:0u; j < 4u; j++) if (a[j] != b[j]) return a[j] < b[j];
    }
    return true;
}

void flw_captureMaterial(ivec2 pixel, float depth, vec4 albedo, vec4 data, vec4 normal,
        vec4 water, vec2 waterDepth, uint width) {
    if (!flw_oitActive) return;
    if (flw_needsLight == 0u) flw_needsLight = 1u;
    uint node = flw_reserveSlots(4u);
    if (node + 3u >= uint(flw_nodes.length())) { if (flw_overflow == 0u) flw_overflow = 1u; return; }
    uint previous = atomicExchange(flw_materialHeads[uint(pixel.y) * width + uint(pixel.x)], node);
    flw_nodes[node] = uvec4(previous, floatBitsToUint(depth), packHalf2x16(albedo.rg), packHalf2x16(albedo.ba));
    flw_nodes[node + 1u] = uvec4(packUnorm2x16(data.xy), packUnorm2x16(data.zw),
            packUnorm2x16(normal.xy), packUnorm2x16(normal.zw));
    flw_nodes[node + 2u] = uvec4(packUnorm2x16(water.xy), packUnorm2x16(water.zw), floatBitsToUint(waterDepth));
    flw_nodes[node + 3u] = uvec4(0u);
}
