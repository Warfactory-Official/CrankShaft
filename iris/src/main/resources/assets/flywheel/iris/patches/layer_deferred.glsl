_FLW_FAR_COLOR _flw_farLayer(uint flw_farNode, ivec2 texel, vec2 texcoord, _FLW_FAR_COLOR flw_farBackground, float flw_farBack);

vec3 _flw_farViewPosition(vec2 coord, float depth) {
    return _FLW_FAR_VIEW_POSITION;
}

uint flw_farHead(ivec2 pixel) {
    if (!flw_oitActive || flw_overflow != 0u || any(lessThan(pixel, ivec2(0)))
        || any(greaterThanEqual(uvec2(pixel), uvec2(screenSize))))
    return 0xffffffffu;
    return _FLW_FAR_HEADS[uint(pixel.y) * uint(screenSize.x) + uint(pixel.x)];
}

// Lists run farthest first: captures behind the opaque surface lead, then the surfaces behind the front surface.
bool flw_farBehind(float depth, vec2 coord, float front, vec4 plane, bool planar) {
    if (!planar) return _FLW_REVERSED_DEPTH ? depth < front : depth > front;
    vec3 position = _flw_farViewPosition(coord, depth);
    return dot(plane, vec4(position, 1.0)) > 1e-4 * (1.0 - position.z);
}

float flw_farDepth(ivec2 pixel, float front, vec4 plane, bool planar, float opaque) {
    vec2 coord = (vec2(pixel) + 0.5) / screenSize;
    float back = opaque;
    for (uint node = flw_farHead(pixel); node != 0xffffffffu; node = flw_nodes[node].x) {
        float depth = uintBitsToFloat(flw_nodes[node].y);
        if (_FLW_REVERSED_DEPTH ? depth <= opaque : depth >= opaque) continue;
        if (!flw_farBehind(depth, coord, front, plane, planar)) break;
        back = depth;
    }
    return back;
}

_FLW_FAR_COLOR flw_farComposite(ivec2 pixel, float front, vec4 plane, bool planar, _FLW_FAR_COLOR color) {
    uint node = flw_farHead(pixel);
    if (node == 0xffffffffu) return color;
    vec2 coord = (vec2(pixel) + 0.5) / screenSize;
    float opaque = _FLW_FAR_OPAQUE;
    float back = opaque;
    for (; node != 0xffffffffu; node = flw_nodes[node].x) {
        float depth = uintBitsToFloat(flw_nodes[node].y);
        if (_FLW_REVERSED_DEPTH ? depth <= opaque : depth >= opaque) continue;
        if (!flw_farBehind(depth, coord, front, plane, planar)) break;
        color = _flw_farLayer(node, pixel, coord, color, back);
        back = depth;
    }
    return color;
}
