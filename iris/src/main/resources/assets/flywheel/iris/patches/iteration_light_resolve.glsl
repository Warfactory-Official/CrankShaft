float flw_iterationExposure();
vec3 flw_iterationHdrToDisplay(vec3 color, float exposure);
vec3 flw_iterationDisplayToHdr(vec3 color, float exposure);

struct FlwLightComposite {
    vec3 color;
    float exposure;
    bool display;
};

// Alpha-zero nodes carry HDR light; other nodes carry premultiplied display color, farthest first.
void flw_composeLight(inout FlwLightComposite composite, vec4 value) {
    if (value.a > 0.0) {
        if (!composite.display) {
            composite.color = flw_iterationHdrToDisplay(composite.color, composite.exposure);
            composite.display = true;
        }
        composite.color = value.rgb + (1.0 - value.a) * composite.color;
    } else {
        if (composite.display) {
            composite.color = flw_iterationDisplayToHdr(composite.color, composite.exposure);
            composite.display = false;
        }
        composite.color += value.rgb;
    }
}

vec3 flw_finishLight(FlwLightComposite composite) {
    if (composite.display) return flw_iterationDisplayToHdr(composite.color, composite.exposure);
    return composite.color;
}

vec3 flw_resolveLight(vec3 background, ivec2 pixel, float front, float back, uvec2 size) {
    if (!flw_oitActive || flw_overflow != 0u || any(greaterThanEqual(uvec2(pixel), size))) return background;
    if (flw_lightSimpleInitialized(pixel, size)) return flw_resolveSimpleLight(background, pixel, front, back, size);
    uint node = flw_heads[uint(pixel.y) * size.x + uint(pixel.x)];
    if (node == 0xffffffffu) return background;
    if ((node & FLW_LIGHT_UNSORTED) != 0u) {
        uint summary = flw_lightSummaryOffset(uint(pixel.y) * size.x + uint(pixel.x), size);
        uint stride = size.x * size.y;
        float nearest = uintBitsToFloat(flw_heads[summary + 3u * stride]);
        float farthest = uintBitsToFloat(flw_heads[summary + 4u * stride]);
        if (_FLW_REVERSED_DEPTH ? farthest > front || nearest <= back : farthest < front || nearest >= back)
            return background;
        if (_FLW_REVERSED_DEPTH ? nearest <= front && farthest > back : nearest >= front && farthest < back)
            return flw_lightSummaryRadiance(summary, stride) + background;
        vec3 light = vec3(0.0);
        for (node &= ~FLW_LIGHT_UNSORTED; node != 0xffffffffu; node = flw_nodes[node].x) {
            uvec4 entry = flw_nodes[node];
            float depth = uintBitsToFloat(entry.y);
            if (_FLW_REVERSED_DEPTH ? depth <= front && depth > back : depth >= front && depth < back)
                light += vec3(unpackHalf2x16(entry.z), unpackHalf2x16(entry.w).x);
        }
        return light + background;
    }
    FlwLightComposite composite = FlwLightComposite(background, flw_iterationExposure(), false);
    while (node != 0xffffffffu) {
        uvec4 entry = flw_nodes[node];
        float depth = uintBitsToFloat(entry.y);
        if (_FLW_REVERSED_DEPTH ? depth > front : depth < front) break;
        if (_FLW_REVERSED_DEPTH ? depth > back : depth < back)
            flw_composeLight(composite, vec4(unpackHalf2x16(entry.z), unpackHalf2x16(entry.w)));
        node = entry.x;
    }
    return flw_finishLight(composite);
}
