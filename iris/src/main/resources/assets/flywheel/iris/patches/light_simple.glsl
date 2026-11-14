const bool flw_lightSimpleEnabled = false;
const uint FLW_LIGHT_SIMPLE_CLASSIFIED = 16u;
const uint FLW_LIGHT_COMPLEX = 4u;
const uint FLW_LIGHT_SIMPLE_INITIALIZED = 0x80000000u;

bool flw_lightSimpleInitialized(ivec2 pixel, uvec2 size) {
    return flw_lightSimpleEnabled && all(lessThan(uvec2(pixel), size))
            && (_FLW_SIMPLE_HEADS[flw_lightDemandOffset(uint(pixel.y) * size.x + uint(pixel.x), size)]
                    & FLW_LIGHT_SIMPLE_INITIALIZED) != 0u;
}

bool flw_lightSimpleCandidate(uint flags) {
    return flw_lightSimpleEnabled && ((flags & FLW_LIGHT_SIMPLE_INITIALIZED) != 0u
            || (flags & (FLW_LIGHT_SIMPLE_CLASSIFIED | FLW_LIGHT_COMPLEX | 3u))
                    == (FLW_LIGHT_SIMPLE_CLASSIFIED | 3u));
}

uint flw_lightSimpleMode(ivec2 pixel, uvec2 size) {
    return _FLW_SIMPLE_HEADS[flw_lightDemandOffset(uint(pixel.y) * size.x + uint(pixel.x), size)];
}

vec3 flw_lightSimpleFront(uint pixel, uint summary, uint stride) {
    return uintBitsToFloat(uvec3(_FLW_SIMPLE_HEADS[pixel], _FLW_SIMPLE_HEADS[summary],
            _FLW_SIMPLE_HEADS[summary + stride]));
}

vec3 flw_lightSimpleRear(uint summary, uint stride) {
    return uintBitsToFloat(uvec3(_FLW_SIMPLE_HEADS[summary + 2u * stride], _FLW_SIMPLE_HEADS[summary + 3u * stride],
            _FLW_SIMPLE_HEADS[summary + 4u * stride]));
}

vec3 flw_resolveSimpleLight(vec3 background, ivec2 pixel, float front, float back, uvec2 size) {
    uint offset = uint(pixel.y) * size.x + uint(pixel.x);
    uint summary = flw_lightSummaryOffset(offset, size);
    uint stride = size.x * size.y;
    float cut = uintBitsToFloat(_FLW_SIMPLE_HEADS[flw_lightDemandOffset(offset, size)] & 0x3fffffffu);
    vec3 light = vec3(0.0);
    if (_FLW_REVERSED_DEPTH ? front > cut && back <= cut : front < cut && back >= cut)
        light += flw_lightSimpleFront(offset, summary, stride);
    if (_FLW_REVERSED_DEPTH ? front >= cut && back < cut : front <= cut && back > cut)
        light += flw_lightSimpleRear(summary, stride);
    return light + background;
}

// The native front cut is in [0, 1], so its two high bits are free without losing a depth bit.
// Each fragment keeps the linked path's half precision; additive regrouping changes only floating-point sum order.
void flw_captureSimpleLight(ivec2 pixel, float depth, vec4 value, uvec2 size, uint mode, float cut, float opaque) {
    if (_FLW_REVERSED_DEPTH ? depth <= opaque : depth >= opaque) return;
    uint offset = uint(pixel.y) * size.x + uint(pixel.x);
    uint modeOffset = flw_lightDemandOffset(offset, size);
    uint summary = flw_lightSummaryOffset(offset, size);
    uint stride = size.x * size.y;
    bool initialize = (mode & FLW_LIGHT_SIMPLE_INITIALIZED) == 0u;
    if (initialize)
        _FLW_SIMPLE_HEADS[modeOffset] = (floatBitsToUint(cut) & 0x3fffffffu) | FLW_LIGHT_SIMPLE_INITIALIZED;
    vec3 radiance = vec3(unpackHalf2x16(packHalf2x16(value.rg)),
            unpackHalf2x16(packHalf2x16(value.ba)).x);
    if (_FLW_REVERSED_DEPTH ? depth > cut : depth < cut) {
        vec3 front = (initialize ? vec3(0.0) : flw_lightSimpleFront(offset, summary, stride)) + radiance;
        _FLW_SIMPLE_HEADS[offset] = floatBitsToUint(front.r);
        _FLW_SIMPLE_HEADS[summary] = floatBitsToUint(front.g);
        _FLW_SIMPLE_HEADS[summary + stride] = floatBitsToUint(front.b);
        if (initialize) {
            _FLW_SIMPLE_HEADS[summary + 2u * stride] = 0u;
            _FLW_SIMPLE_HEADS[summary + 3u * stride] = 0u;
            _FLW_SIMPLE_HEADS[summary + 4u * stride] = 0u;
        }
    } else {
        vec3 rear = (initialize ? vec3(0.0) : flw_lightSimpleRear(summary, stride)) + radiance;
        _FLW_SIMPLE_HEADS[summary + 2u * stride] = floatBitsToUint(rear.r);
        _FLW_SIMPLE_HEADS[summary + 3u * stride] = floatBitsToUint(rear.g);
        _FLW_SIMPLE_HEADS[summary + 4u * stride] = floatBitsToUint(rear.b);
        if (initialize) {
            _FLW_SIMPLE_HEADS[offset] = 0u;
            _FLW_SIMPLE_HEADS[summary] = 0u;
            _FLW_SIMPLE_HEADS[summary + stride] = 0u;
        }
    }
}
