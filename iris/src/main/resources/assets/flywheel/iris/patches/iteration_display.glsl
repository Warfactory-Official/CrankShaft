// MIT License
//
// Copyright (c) 2024 Missing Deadlines (Benjamin Wrensch)
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

#ifndef _FLW_ITERATION_DISPLAY
#define _FLW_ITERATION_DISPLAY

const float _FLW_ITERATION_FINITE_WHITE = 1.0 - 1.0 / 65536.0;
const float _FLW_ITERATION_GT_TOE = 0.22;
const float _FLW_ITERATION_GT_SHOULDER = 0.22 + (1.0 - 0.22) * 0.4;
const mat3 _FLW_ITERATION_ABNEY = mat3(
        0.99999976, -1.26657e-7, -1.29064e-9,
        1.67316e-8, 0.99999976, -5.32026e-9,
        -0.00725587, 6.47740e-9, 1.00725580);
const mat3 _FLW_ITERATION_AGX_IN = mat3(
        0.842479062253094, 0.0784335999999992, 0.0792237451477643,
        0.0423282422610123, 0.878468636469772, 0.0791661274605434,
        0.0423756549057051, 0.0784336, 0.879142973793104);
const mat3 _FLW_ITERATION_AGX_OUT = mat3(
        1.19687900512017, -0.0980208811401368, -0.0990297440797205,
        -0.0528968517574562, 1.15190312990417, -0.0989611768448433,
        -0.0529716355144438, -0.0980434501171241, 1.15107367264116);
const mat3 _FLW_ITERATION_ACES_IN = mat3(
        0.59719, 0.35458, 0.04823,
        0.07600, 0.90834, 0.01566,
        0.02840, 0.13383, 0.83777);
const mat3 _FLW_ITERATION_ACES_OUT = mat3(
        1.60475, -0.53108, -0.07367,
        -0.10208, 1.10813, -0.00605,
        -0.00327, -0.07276, 1.07602);

vec3 _flw_iterationLinearToSrgb(vec3 color) {
    return mix(color * 12.92, pow(color, vec3(1.0 / 2.4)) * 1.055 - 0.055,
            step(vec3(0.0031308), color));
}

vec3 _flw_iterationSrgbToLinear(vec3 color) {
    return mix(color / 12.92, pow((color + 0.055) / 1.055, vec3(2.4)),
            step(vec3(0.04045), color));
}

vec3 _flw_iterationAbney(vec3 color) {
    #ifdef ABNEY_EFFECT_CORRECTION
    return color * _FLW_ITERATION_ABNEY;
    #else
    return color;
    #endif
}

vec3 _flw_iterationInverseAbney(vec3 color) {
    #ifdef ABNEY_EFFECT_CORRECTION
    return color * inverse(_FLW_ITERATION_ABNEY);
    #else
    return color;
    #endif
}

vec3 _flw_iterationAgxContrast(vec3 x) {
    return (((((15.5 * x - 40.14) * x + 31.96) * x - 6.868) * x
            + 0.4298) * x + 0.1191) * x - 0.00232;
}

#ifdef HDR_ENABLED
vec3 _flw_iterationAgxHdrResponse(vec3 x, float expansion) {
    vec3 response = _flw_iterationAgxContrast(x);
    vec3 blend = clamp(x * 20.0 - 11.9481, 0.0, 1.0);
    response = mix(response, x * 2.063794 - 0.7540885, blend);
    vec3 shifted = x - 0.484544 * expansion;
    blend = clamp(shifted * 20.0 - 11.9481, 0.0, 1.0);
    return mix(response, _flw_iterationAgxContrast(shifted) + expansion, blend);
}
#endif

vec3 _flw_iterationInverseAgX(vec3 displayColor) {
    #ifdef HDR_ENABLED
    float outputRange = HdrGamePeakBrightness / HdrGamePaperWhiteBrightness;
    displayColor *= outputRange;
    #endif
    vec3 response = displayColor * inverse(_FLW_ITERATION_AGX_OUT);
    vec3 low = vec3(0.0);
    #ifdef HDR_ENABLED
    float expansion = outputRange - 1.0;
    vec3 high = vec3(2.0 + 0.484544 * expansion);
    #else
    vec3 high = vec3(1.0);
    response = clamp(response, _flw_iterationAgxContrast(low), _flw_iterationAgxContrast(high));
    #endif
    for (int i = 0; i < 20; i++) {
        vec3 middle = (low + high) * 0.5;
        #ifdef HDR_ENABLED
        vec3 value = _flw_iterationAgxHdrResponse(middle, expansion);
        #else
        vec3 value = _flw_iterationAgxContrast(middle);
        #endif
        bvec3 below = lessThan(value, response);
        low = mix(low, middle, below);
        high = mix(middle, high, below);
    }
    #ifdef HDR_ENABLED
    vec3 color = AGX_HDR_MIDGREY * exp2(((low + high) * 0.5 - 0.5) * AGX_HDR_EV);
    #else
    vec3 color = 0.18 * exp2(((low + high) * 0.5 - 0.5) * AGX_EV);
    #endif
    color *= inverse(_FLW_ITERATION_AGX_IN);
    return _flw_iterationInverseAbney(color) / 2.3;
}

vec3 _flw_iterationInverseACES(vec3 displayColor) {
    vec3 response = _flw_iterationSrgbToLinear(displayColor) * inverse(_FLW_ITERATION_ACES_OUT);
    response = clamp(response, 0.0, _FLW_ITERATION_FINITE_WHITE);
    vec3 p = 0.0245786 - response * 0.4329510;
    vec3 q = 0.000090537 + response * 0.238081;
    vec3 color = (2.0 * q) / (p + sqrt(p * p + 4.0 * (1.0 - response) * q));
    color *= inverse(_FLW_ITERATION_ACES_IN);
    return _flw_iterationInverseAbney(color) / 1.4;
}

float _flw_iterationGtResponse(float color) {
    if (color < _FLW_ITERATION_GT_TOE)
        return mix(pow(color, 1.33) / pow(_FLW_ITERATION_GT_TOE, 0.33), color,
                smoothstep(0.0, _FLW_ITERATION_GT_TOE, color));
    if (color < _FLW_ITERATION_GT_SHOULDER) return color;
    const float falloff = -1.44269502 / (1.0 - _FLW_ITERATION_GT_SHOULDER);
    return 1.0 - (1.0 - _FLW_ITERATION_GT_SHOULDER)
            * exp2(falloff * (color - _FLW_ITERATION_GT_SHOULDER));
}

float _flw_iterationInverseGtChannel(float response) {
    response = min(response, _FLW_ITERATION_FINITE_WHITE);
    float color = response;
    if (response < _FLW_ITERATION_GT_TOE) {
        float low = 0.0;
        float high = _FLW_ITERATION_GT_TOE;
        for (int i = 0; i < 20; i++) {
            float middle = (low + high) * 0.5;
            float value = _flw_iterationGtResponse(middle);
            if (value < response) low = middle;
            else high = middle;
        }
        color = (low + high) * 0.5;
    } else if (response >= _FLW_ITERATION_GT_SHOULDER) {
        const float falloff = -1.44269502 / (1.0 - _FLW_ITERATION_GT_SHOULDER);
        color = _FLW_ITERATION_GT_SHOULDER
                + log2((1.0 - response) / (1.0 - _FLW_ITERATION_GT_SHOULDER)) / falloff;
    }
    return pow(color, 1.0 / 0.9) / 0.6;
}

vec3 _flw_iterationInverseGT(vec3 displayColor) {
    vec3 response = _flw_iterationSrgbToLinear(displayColor);
    return vec3(_flw_iterationInverseGtChannel(response.r),
            _flw_iterationInverseGtChannel(response.g), _flw_iterationInverseGtChannel(response.b));
}

vec3 _flw_iterationInverseNone(vec3 displayColor) {
    return pow(displayColor, vec3(2.2));
}

vec3 _flw_iterationForwardAgX(vec3 color) {
    color = _flw_iterationAbney(color * 2.3) * _FLW_ITERATION_AGX_IN;
    #ifdef HDR_ENABLED
    float outputRange = HdrGamePeakBrightness / HdrGamePaperWhiteBrightness;
    vec3 x = max(log2(color / AGX_HDR_MIDGREY), vec3(-AGX_HDR_EV * 0.5));
    x = (x + AGX_HDR_EV * 0.5) / AGX_HDR_EV;
    return (_flw_iterationAgxHdrResponse(x, outputRange - 1.0) * _FLW_ITERATION_AGX_OUT) / outputRange;
    #else
    vec3 x = clamp(log2(color / 0.18), -AGX_EV * 0.5, AGX_EV * 0.5);
    x = (x + AGX_EV * 0.5) / AGX_EV;
    return _flw_iterationAgxContrast(x) * _FLW_ITERATION_AGX_OUT;
    #endif
}

vec3 _flw_iterationForwardACES(vec3 color) {
    color = _flw_iterationAbney(color * 1.4) * _FLW_ITERATION_ACES_IN;
    vec3 numerator = color * (color + 0.0245786) - 0.000090537;
    vec3 denominator = color * (color + 0.4329510) + 0.238081;
    return _flw_iterationLinearToSrgb((numerator / denominator) * _FLW_ITERATION_ACES_OUT);
}

vec3 _flw_iterationForwardGT(vec3 color) {
    color = pow(color * 0.6, vec3(0.9));
    return _flw_iterationLinearToSrgb(vec3(_flw_iterationGtResponse(color.r),
            _flw_iterationGtResponse(color.g), _flw_iterationGtResponse(color.b)));
}

vec3 _flw_iterationForwardNone(vec3 color) {
    return pow(color, vec3(1.0 / 2.2));
}

#define AgX _flw_iterationInverseAgX
#define ACES _flw_iterationInverseACES
#define GT _flw_iterationInverseGT
#define None _flw_iterationInverseNone

// Inverts Final_FS exposure and tone mapping; unavailable saturated colors project into finite HDR RGB.
vec3 flw_iterationDisplayToHdr(vec3 displayColor, float exposure) {
    return clamp(TONEMAP_OPERATOR(clamp(displayColor, 0.0, 1.0)) / exposure, 0.0, 65504.0);
}

#undef AgX
#undef ACES
#undef GT
#undef None

#define AgX _flw_iterationForwardAgX
#define ACES _flw_iterationForwardACES
#define GT _flw_iterationForwardGT
#define None _flw_iterationForwardNone

vec3 flw_iterationHdrToDisplay(vec3 color, float exposure) {
    return clamp(TONEMAP_OPERATOR(color * exposure), 0.0, 1.0);
}

#undef AgX
#undef ACES
#undef GT
#undef None

float flw_iterationExposure() {
    return texelFetch(pixelData2D, ivec2(PIXELDATA_EXPOSURE, 0), 0).y;
}

vec3 flw_iterationCompositeDisplay(vec3 premultipliedDisplay, float transmission, vec3 background) {
    float exposure = flw_iterationExposure();
    vec3 displayColor = premultipliedDisplay + transmission * flw_iterationHdrToDisplay(background, exposure);
    return flw_iterationDisplayToHdr(displayColor, exposure);
}

#endif
