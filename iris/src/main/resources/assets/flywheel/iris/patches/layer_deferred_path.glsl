vec2 _flw_farRefraction(uint flw_farNode, ivec2 texel, vec2 texcoord, float flw_farBack, out vec4 flw_farPlane);

const uint FLW_FAR_PATH = 8u;

float _flw_farOpaqueDepth(ivec2 pixel) {
    return _FLW_FAR_OPAQUE;
}

_FLW_FAR_COLOR _flw_farOpaqueColor(ivec2 pixel) {
    return _FLW_FAR_OPAQUE_COLOR;
}

uint flw_farNearest(ivec2 pixel, float front, vec4 plane, bool planar, float opaque, out float back) {
    vec2 coord = (vec2(pixel) + 0.5) / screenSize;
    uint nearest = 0xffffffffu;
    float nearestDepth = opaque;
    back = opaque;
    for (uint node = flw_farHead(pixel); node != 0xffffffffu; node = flw_nodes[node].x) {
        float depth = uintBitsToFloat(flw_nodes[node].y);
        if (_FLW_REVERSED_DEPTH ? depth <= opaque : depth >= opaque) continue;
        if (!flw_farBehind(depth, coord, front, plane, planar)) break;
        back = nearestDepth;
        nearest = node;
        nearestDepth = depth;
    }
    return nearest;
}

// Each rear surface refracts the composed image behind it: the path follows the nearest surface behind each one at
// its refraction target, then shades back to front. Surfaces past the path budget composite unshifted. The pack's
// own (filtered) read stands for the opaque surface while the path stays at its start.
_FLW_FAR_COLOR flw_farPath(ivec2 pixel, float front, vec4 plane, bool planar, _FLW_FAR_COLOR background) {
    ivec2 start = pixel;
    uint nodes[FLW_FAR_PATH];
    ivec2 pixels[FLW_FAR_PATH];
    float depths[FLW_FAR_PATH];
    uint count = 0u;
    for (; count < FLW_FAR_PATH; count++) {
        float back;
        uint node = flw_farNearest(pixel, front, plane, planar, _flw_farOpaqueDepth(pixel), back);
        if (node == 0xffffffffu) break;
        float depth = uintBitsToFloat(flw_nodes[node].y);
        nodes[count] = node;
        pixels[count] = pixel;
        depths[count] = depth;
        vec4 nodePlane;
        vec2 refracted = _flw_farRefraction(node, pixel, (vec2(pixel) + 0.5) / screenSize, back, nodePlane);
        ivec2 target = clamp(ivec2(refracted * screenSize), ivec2(0), ivec2(screenSize) - 1);
        float targetDepth = flw_farDepth(target, depth, nodePlane, true, _flw_farOpaqueDepth(target));
        if (_FLW_REVERSED_DEPTH ? depth <= targetDepth : depth >= targetDepth) target = pixel;
        pixel = target;
        front = depth;
        plane = nodePlane;
        planar = true;
    }
    _FLW_FAR_COLOR color = flw_farComposite(pixel, front, plane, planar,
            pixel == start ? background : _flw_farOpaqueColor(pixel));
    float back = flw_farDepth(pixel, front, plane, planar, _flw_farOpaqueDepth(pixel));
    for (uint i = count; i > 0u; i--) {
        color = _flw_farLayer(nodes[i - 1u], pixels[i - 1u], (vec2(pixels[i - 1u]) + 0.5) / screenSize, color, back);
        back = depths[i - 1u];
    }
    return color;
}
