layout(std430, binding = 0) coherent buffer FlwHeads { uint flw_heads[]; };
layout(std430, binding = 1) coherent buffer FlwNodes { uvec4 flw_nodes[]; };
layout(std430, binding = 2) coherent buffer FlwCounts { uint flw_count; uint flw_overflow; uint flw_maxLayers; uint flw_pad; };
uniform bool flw_oitActive;

void flw_captureLayer(vec4 a, vec4 n, vec4 m) {
    if (!flw_oitActive) return;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    uint offset = uint(pixel.y * int(screenSize.x) + pixel.x);
    float depth = gl_FragCoord.z;
    if (depth >= texelFetch(depthtex1, pixel, 0).r) return;
    uint node = atomicAdd(flw_count, 1u);
    if (node >= uint(flw_nodes.length() / 2)) { atomicOr(flw_overflow, 1u); return; }
    uint previous = atomicExchange(flw_heads[offset], node);
    flw_nodes[node * 2u] = uvec4(previous, floatBitsToUint(depth), packUnorm4x8(a), packSnorm2x16(n.xy));
    flw_nodes[node * 2u + 1u] = uvec4(packSnorm2x16(n.zw), packUnorm2x16(m.xy), packUnorm2x16(m.zw), 0u);
}
