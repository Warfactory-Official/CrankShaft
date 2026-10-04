vec3 flw_resolveRefractedLight(vec3 background, vec2 coord, float back, vec4 plane) {
    if (!flw_oitActive || flw_overflow != 0u) return background;
    ivec2 pixel = ivec2(coord * screenSize);
    uint node = flw_heads[uint(pixel.y) * uint(screenSize.x) + uint(pixel.x)];
    vec3 light = vec3(0.0);
    float transmission = 1.0;
    while (node != 0xffffffffu) {
        uvec4 entry = flw_nodes[node];
        float depth = uintBitsToFloat(entry.y);
        if (depth >= back) break;
        vec3 position = ViewPos_From_ScreenPos(coord, depth);
        if (dot(plane, vec4(position, 1.0)) >= 0.0) {
            vec4 value = vec4(unpackHalf2x16(entry.z), unpackHalf2x16(entry.w));
            light += transmission * value.rgb;
            transmission *= 1.0 - value.a;
        }
        node = entry.x;
    }
    return light + transmission * background;
}
