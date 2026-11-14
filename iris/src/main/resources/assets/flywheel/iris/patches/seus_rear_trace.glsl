void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (!flw_oitActive || flw_overflow != 0u || any(greaterThanEqual(pixel, ivec2(screenSize)))) return;
    // The packed-HDR fragment of this list pixel.
    _flw_fragCoord = vec4(vec2(pixel) + 0.5 + HalfScreen * screenSize, 0.5, 1.0);
    _FLW_PIXEL_GLOBALS
    vec2 coord = (vec2(pixel) + 0.5) / screenSize;
    bool inputs = false;
    // The native surface keeps the pack's own filtered reflection; a refraction path reaching it reads none. Lists
    // run farthest first.
    float front = texelFetch(depthtex0, pixel, 0).r;
    float opaque = texelFetch(depthtex1, pixel, 0).r;
    float back = opaque;
    for (uint node = flw_heads[uint(pixel.y) * uint(screenSize.x) + uint(pixel.x)]; node != 0xffffffffu;
    node = flw_nodes[node].x) {
        float depth = uintBitsToFloat(flw_nodes[node].y);
        if (_FLW_REVERSED_DEPTH ? depth <= opaque : depth >= opaque) continue;
        vec4 reflection = vec4(0.0);
        if (depth != front) {
            if (!inputs) _flw_vertexInputs();
            inputs = true;
            reflection = _flw_rearTrace(node, pixel, coord, back);
        }
        flw_nodes[node + 2u] = uvec4(packHalf2x16(reflection.rg), packHalf2x16(reflection.ba), 0u, 0u);
        back = depth;
    }
}
