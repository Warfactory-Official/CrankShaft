layout(std430, binding = 0) coherent buffer FlwHeads { uint flw_heads[]; };
layout(std430, binding = 1) coherent buffer FlwNodes { uvec4 flw_nodes[]; };
_FLW_CAPTURE_COUNTS_2
uniform bool flw_oitActive;
_FLW_CAPTURE_SNAPSHOT
bool flw_captureUnlit = false;

void flw_setCaptureUnlit(bool unlit) { flw_captureUnlit = unlit; }

// Stops at 2^31, above any pool, so callers reject it; a 2^32 wrap would reissue linked nodes into cyclic lists.
uint flw_reserveSlots(uint slots) { return flw_count >= 0x80000000u ? 0x80000000u : atomicAdd(flw_count, slots); }

void flw_captureLayer(vec4 a, vec4 n, vec4 m) {
    if (!flw_oitActive) return;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    uint offset = uint(pixel.y * int(screenSize.x) + pixel.x);
    float depth = gl_FragCoord.z;
    float limit = flw_captureLimit(pixel, texelFetch(depthtex1, pixel, 0).r);
    if (_FLW_REVERSED_DEPTH ? depth <= limit : depth >= limit) return;
    if (flw_needsLight == 0u) flw_needsLight = 1u;
    uint node = flw_reserveSlots(4u);
    if (node + 3u >= uint(flw_nodes.length())) { if (flw_overflow == 0u) flw_overflow = 1u; return; }
    uint previous = atomicExchange(flw_heads[offset], node);
    flw_nodes[node] = uvec4(previous, floatBitsToUint(depth), packUnorm4x8(a), packSnorm2x16(n.xy));
    flw_nodes[node + 1u] = uvec4(packSnorm2x16(n.zw), packUnorm2x16(m.xy), packUnorm2x16(m.zw),
            flw_captureUnlit ? 0x80000000u : 0u);
    flw_nodes[node + 2u] = uvec4(0u);
    flw_nodes[node + 3u] = uvec4(0u);
}
