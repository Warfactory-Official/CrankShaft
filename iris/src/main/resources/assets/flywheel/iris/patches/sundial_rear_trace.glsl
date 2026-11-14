void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    if (!flw_oitActive || flw_overflow != 0u || any(greaterThanEqual(pixel, ivec2(screenSize)))) return;
    _flw_fragCoord = vec4(vec2(pixel) + 0.5, 0.5, 1.0);
    texcoord = (vec2(pixel) + 0.5) / screenSize;
    _FLW_PIXEL_GLOBALS
    bool inputs = false;
    // The native surface keeps the pack's own filtered reflection. Lists run farthest first.
    float front = texelFetch(depthtex0, pixel, 0).r;
    float back = texelFetch(depthtex1, pixel, 0).r;
    for (uint node = flw_heads[uint(pixel.y) * uint(screenSize.x) + uint(pixel.x)]; node != 0xffffffffu;
    node = flw_nodes[node].x) {
        float depth = uintBitsToFloat(flw_nodes[node].y);
        if (_FLW_REVERSED_DEPTH ? depth <= back : depth >= back) continue;
        if (depth != front) {
            if (!inputs) {
                _flw_vertexInputs();
                texcoord = (vec2(pixel) + 0.5) / screenSize;
            }
            inputs = true;
            vec3 light;
            vec4 reflection;
            vec3 weight;
            _flw_rearMirror(node, pixel, texcoord, back, light, reflection, weight);
            flw_nodes[node + 2u].xy = uvec2(packHalf2x16(reflection.rg), packHalf2x16(reflection.ba));
            flw_nodes[node + 3u] = uvec4(packHalf2x16(light.rg), packHalf2x16(vec2(light.b, weight.r)),
                    packHalf2x16(weight.gb), 0u);
        }
        back = depth;
    }
}
