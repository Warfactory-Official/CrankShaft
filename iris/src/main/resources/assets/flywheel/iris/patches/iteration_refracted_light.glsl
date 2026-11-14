vec3 flw_resolveRefractedLight(vec3 background, vec2 coord, float back, vec4 plane) {
    if (!flw_oitActive || flw_overflow != 0u) return background;
    ivec2 pixel = ivec2(coord * screenSize);
    if (any(greaterThanEqual(uvec2(pixel), uvec2(screenSize)))) return background;
    uint node = flw_heads[uint(pixel.y) * uint(screenSize.x) + uint(pixel.x)];
    vec3 light = vec3(0.0);
    if (node != 0xffffffffu && (node & FLW_LIGHT_UNSORTED) != 0u) {
        uint summary = flw_lightSummaryOffset(uint(pixel.y) * uint(screenSize.x) + uint(pixel.x), uvec2(screenSize));
        uint stride = uint(screenSize.x) * uint(screenSize.y);
        float nearest = uintBitsToFloat(flw_heads[summary + 3u * stride]);
        float farthest = uintBitsToFloat(flw_heads[summary + 4u * stride]);
        if (_FLW_REVERSED_DEPTH ? nearest <= back : nearest >= back) return background;
        float nearSide = dot(plane, vec4(ViewPos_From_ScreenPos(coord, nearest), 1.0));
        float farSide = dot(plane, vec4(ViewPos_From_ScreenPos(coord, farthest), 1.0));
        if (nearSide < 0.0 && farSide < 0.0) return background;
        if ((_FLW_REVERSED_DEPTH ? farthest > back : farthest < back) && nearSide >= 0.0 && farSide >= 0.0)
            return flw_lightSummaryRadiance(summary, stride) + background;
        for (node &= ~FLW_LIGHT_UNSORTED; node != 0xffffffffu; node = flw_nodes[node].x) {
            uvec4 entry = flw_nodes[node];
            float depth = uintBitsToFloat(entry.y);
            if ((_FLW_REVERSED_DEPTH ? depth > back : depth < back)
                    && dot(plane, vec4(ViewPos_From_ScreenPos(coord, depth), 1.0)) >= 0.0)
                light += vec3(unpackHalf2x16(entry.z), unpackHalf2x16(entry.w).x);
        }
        return light + background;
    }
    FlwLightComposite composite = FlwLightComposite(background, flw_iterationExposure(), false);
    while (node != 0xffffffffu) {
        uvec4 entry = flw_nodes[node];
        float depth = uintBitsToFloat(entry.y);
        if ((_FLW_REVERSED_DEPTH ? depth > back : depth < back)
                && dot(plane, vec4(ViewPos_From_ScreenPos(coord, depth), 1.0)) >= 0.0) {
            vec4 value = vec4(unpackHalf2x16(entry.z), unpackHalf2x16(entry.w));
            flw_composeLight(composite, value);
        }
        node = entry.x;
    }
    return flw_finishLight(composite);
}
