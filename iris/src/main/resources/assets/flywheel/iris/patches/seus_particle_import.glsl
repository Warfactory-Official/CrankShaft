void flw_importParticle(ivec2 pixel, float opaque) {
    if (!flw_oitActive) return;
    uint offset = uint(pixel.y) * uint(screenSize.x) + uint(pixel.x);
    uint particle = flw_particleOffset(uvec2(pixel), uint(screenSize.x));
    uvec4 material = flw_nodes[particle];
    if (material.x == 0xffffffffu) return;
    float depth = uintBitsToFloat(material.y);
    if (_FLW_REVERSED_DEPTH ? depth <= opaque : depth >= opaque) return;
    uint previous = flw_heads[offset];
    flw_nodes[particle].x = previous;
    flw_heads[offset] = particle;
}
