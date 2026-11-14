void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (!flw_oitActive || flw_overflow != 0u || any(greaterThanEqual(pixel, ivec2(screenSize)))) return;
    _flw_fragCoord = vec4(vec2(pixel) + 0.5, 0.5, 1.0);
    _FLW_PIXEL_GLOBALS
    bool inputs = false;
    // The native surface keeps the pack's own filtered trace.
    float front = texelFetch(depthtex0, pixel, 0).x;
    for (uint node = flw_materialHeads[uint(pixel.y) * uint(screenSize.x) + uint(pixel.x)]; node != 0xffffffffu;
    node = flw_nodes[node].x) {
        if (uintBitsToFloat(flw_nodes[node].y) == front) continue;
        if (!inputs) _flw_vertexInputs();
        inputs = true;
        vec4 specular = _flw_rearSpecular(node);
        flw_nodes[node + 3u].xy = uvec2(packHalf2x16(specular.rg), packHalf2x16(specular.ba));
    }
}
