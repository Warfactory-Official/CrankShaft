void flw_captureSeus(vec4 material, vec4 attributes, vec3 motion) {
    if (!flw_oitActive || UnpackTwo8BitFrom16Bit(material.y).y == 0.0) return;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = gl_FragCoord.z;
    if (_FLW_SEUS_OPAQUE_READY && depth >= texelFetch(depthtex1, pixel, 0).r) return;
    uint node = flw_reserveSlots(2u);
    if (node + 1u >= uint(flw_nodes.length())) { atomicOr(flw_overflow, 1u); return; }
    uint previous = atomicExchange(flw_heads[uint(pixel.y) * uint(screenSize.x) + uint(pixel.x)], node);
    flw_nodes[node] = uvec4(previous, floatBitsToUint(depth), packUnorm2x16(material.xy), packUnorm2x16(material.zw));
    flw_nodes[node + 1u] = uvec4(packUnorm2x16(attributes.xy), packUnorm2x16(attributes.zw),
            packHalf2x16(motion.xy), packHalf2x16(vec2(motion.z, 0.0)));
}
