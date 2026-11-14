bool flw_particleBefore(uvec4 material, uvec4 attributes, uvec4 previous, uvec4 previousAttributes) {
    if (_FLW_REVERSED_DEPTH && uintBitsToFloat(material.y) != uintBitsToFloat(previous.y))
        return uintBitsToFloat(material.y) > uintBitsToFloat(previous.y);
    if (!_FLW_REVERSED_DEPTH && material.y != previous.y) return material.y < previous.y;
    if (material.z != previous.z) return material.z < previous.z;
    if (material.w != previous.w) return material.w < previous.w;
    if (attributes.x != previousAttributes.x) return attributes.x < previousAttributes.x;
    return attributes.y <= previousAttributes.y;
}

void flw_captureParticle(vec4 material, vec4 attributes) {
    uvec2 pixel = uvec2(gl_FragCoord.xy);
    if (any(greaterThanEqual(pixel, uvec2(screenSize) / 2u + 1u))) return;
    uint offset = flw_particleOffset(pixel, uint(screenSize.x));
    uvec4 record = uvec4(0u, floatBitsToUint(gl_FragCoord.z),
            packUnorm2x16(material.xy), packUnorm2x16(material.zw));
    uvec4 data = uvec4(packUnorm2x16(attributes.xy), packUnorm2x16(attributes.zw), 0u, 0u);
    uvec4 previous = flw_nodes[offset];
    if (previous.x == 0xffffffffu || flw_particleBefore(record, data, previous, flw_nodes[offset + 1u])) {
        flw_nodes[offset] = record;
        flw_nodes[offset + 1u] = data;
    }
}
