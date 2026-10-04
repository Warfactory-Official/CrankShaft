layout(std430, binding = 5) buffer FlwOpaque { uvec4 flw_opaque[]; };
layout(std430, binding = 6) coherent buffer FlwMaterialHeads { uint flw_materialHeads[]; };

bool flw_materialBefore(uint left, uint right) {
    for (uint i=0u; i<3u; i++) {
        uvec4 a=flw_nodes[left+i], b=flw_nodes[right+i];
        for (uint j=i==0u?1u:0u; j<4u; j++) if(a[j]!=b[j]) return a[j]<b[j];
    }
    return true;
}

uint flw_materialAt(uint pixel, int layer) {
    uint node = flw_materialHeads[pixel];
    for (int i = 0; i < layer && node != 0xffffffffu; ++i) node = flw_nodes[node].x;
    return node;
}

void flw_captureMaterial(ivec2 pixel, float depth, vec4 albedo, vec4 data, vec4 normal,
                         vec4 water, vec2 waterDepth, uint width) {
    if (!flw_oitActive) return;
    uint node = flw_reserveSlots(3u);
    if (node + 2u >= uint(flw_nodes.length())) { atomicOr(flw_overflow, 1u); return; }
    uint previous = atomicExchange(flw_materialHeads[uint(pixel.y) * width + uint(pixel.x)], node);
    flw_nodes[node] = uvec4(previous, floatBitsToUint(depth), packHalf2x16(albedo.rg), packHalf2x16(albedo.ba));
    flw_nodes[node + 1u] = uvec4(packUnorm2x16(data.xy), packUnorm2x16(data.zw),
                                packUnorm2x16(normal.xy), packUnorm2x16(normal.zw));
    flw_nodes[node + 2u] = uvec4(packUnorm2x16(water.xy), packUnorm2x16(water.zw), floatBitsToUint(waterDepth));
}
