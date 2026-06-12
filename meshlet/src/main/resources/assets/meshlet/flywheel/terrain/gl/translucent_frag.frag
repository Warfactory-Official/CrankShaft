// SPDX-License-Identifier: MIT
// Copyright (C) 2026 movblock

layout(location = 1) in PerVertex {
    vec4 color;
    vec4 misc;
    vec2 fog;
} v_in;

layout(binding = 0) uniform sampler2D Sampler0;

layout(std140, binding = 9) uniform Fog {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

#ifndef _FLW_OIT_INSERT
layout(early_fragment_tests) in;
#endif

#ifdef _FLW_OIT_INSERT
#include "flywheel:internal/mlab.glsl"
#endif

vec4 flw_sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5;
    vec2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = (texelOffset - 0.5) * pixelSize / texelScreenSize + 0.5;
    texelOffset = clamp(texelOffset, 0.0, 1.0);
    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}
vec4 flw_sampleAtlas(sampler2D source, vec2 uv) {
#ifdef FLW_PIXEL_FILTER_LINEAR
    return texture(source, uv);
#else
    vec2 pixelSize = 1.0 / vec2(textureSize(source, 0));
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return flw_sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
#endif
}

#ifndef _FLW_DEPTH_RANGE
float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 0.0;
    } else if (vertexDistance >= fogEnd) {
        return 1.0;
    }
    return (vertexDistance - fogStart) / (fogEnd - fogStart);
}
float total_fog_value(float spherical, float cylindrical, float envStart, float envEnd, float rdStart, float rdEnd) {
    return max(linear_fog_value(spherical, envStart, envEnd), linear_fog_value(cylindrical, rdStart, rdEnd));
}
vec4 apply_fog(vec4 inColor, float spherical, float cylindrical, float envStart, float envEnd, float rdStart, float rdEnd, vec4 fogColor) {
    float fogValue = total_fog_value(spherical, cylindrical, envStart, envEnd, rdStart, rdEnd);
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

#ifndef _FLW_OIT_INSERT
const float _FLW_OIT_NOISE = 0.07;
layout(binding = 3) uniform sampler2D _flw_depthRange;
layout(binding = 4) uniform sampler2D _flw_blueNoise;

float tented_blue_noise(float normalizedDepth) {
    float tentIn = abs(normalizedDepth * 2. - 1);
    float tentIn2 = tentIn * tentIn;
    float tentIn4 = tentIn2 * tentIn2;
    float tent = 1 - (tentIn2 * tentIn4);
    float b = texture(_flw_blueNoise, gl_FragCoord.xy / vec2(64)).r;
    return b * tent;
}
#endif
#endif

#define TRANSPARENCY_WAVELET_RANK 3
#define TRANSPARENCY_WAVELET_COEFFICIENT_COUNT 16

#ifdef _FLW_COLLECT_COEFFS
void add_to_index(inout vec4[4] coefficients, int index, float addend) {
    coefficients[index >> 2][index & 3] = addend;
}
void add_absorbance(inout vec4[4] coefficients, float signal, float depth) {
    depth *= float(TRANSPARENCY_WAVELET_COEFFICIENT_COUNT - 1) / TRANSPARENCY_WAVELET_COEFFICIENT_COUNT;
    int index = clamp(int(floor(depth * TRANSPARENCY_WAVELET_COEFFICIENT_COUNT)), 0, TRANSPARENCY_WAVELET_COEFFICIENT_COUNT - 1);
    index += TRANSPARENCY_WAVELET_COEFFICIENT_COUNT - 1;
    for (int i = 0; i < (TRANSPARENCY_WAVELET_RANK + 1); ++i) {
        int power = TRANSPARENCY_WAVELET_RANK - i;
        int new_index = (index - 1) >> 1;
        float k = float((new_index + 1) & ((1 << power) - 1));
        int wavelet_sign = ((index & 1) << 1) - 1;
        float wavelet_phase = ((index + 1) & 1) * exp2(-power);
        float addend = fma(fma(-exp2(-power), k, depth), float(wavelet_sign), wavelet_phase) * exp2(power * 0.5) * signal;
        add_to_index(coefficients, new_index, addend);
        index = new_index;
    }
    float addend = fma(signal, -depth, signal);
    add_to_index(coefficients, TRANSPARENCY_WAVELET_COEFFICIENT_COUNT - 1, addend);
}
void add_transmittance(inout vec4[4] coefficients, float transmittance, float depth) {
    float absorbance = -log(max(transmittance, 0.00001));
    add_absorbance(coefficients, absorbance, depth);
}
#endif

#ifdef _FLW_EVALUATE
#ifdef _FLW_COEFF_ARRAY
layout(binding = 11) uniform sampler2DArray _flw_coefficients;
#define _FLW_FETCH_COEFFS(dst) dst[0] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 0), 0); \
    dst[1] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 1), 0); \
    dst[2] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 2), 0); \
    dst[3] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 3), 0)
#else
layout(binding = 5) uniform sampler2D _flw_coefficients0;
layout(binding = 6) uniform sampler2D _flw_coefficients1;
layout(binding = 7) uniform sampler2D _flw_coefficients2;
layout(binding = 8) uniform sampler2D _flw_coefficients3;
#define _FLW_FETCH_COEFFS(dst) dst[0] = texelFetch(_flw_coefficients0, ivec2(gl_FragCoord.xy), 0); \
    dst[1] = texelFetch(_flw_coefficients1, ivec2(gl_FragCoord.xy), 0); \
    dst[2] = texelFetch(_flw_coefficients2, ivec2(gl_FragCoord.xy), 0); \
    dst[3] = texelFetch(_flw_coefficients3, ivec2(gl_FragCoord.xy), 0)
#endif

float get_coefficients(in vec4 c[4], int index) {
    return c[index >> 2][index & 3];
}
float signal_corrected_absorbance(in vec4 c[4], float depth, float signal) {
    float scale_coefficient = get_coefficients(c, TRANSPARENCY_WAVELET_COEFFICIENT_COUNT - 1);
    if (scale_coefficient == 0) {
        return 0;
    }
    depth *= float(TRANSPARENCY_WAVELET_COEFFICIENT_COUNT - 1) / TRANSPARENCY_WAVELET_COEFFICIENT_COUNT;
    float scale_coefficient_addend = fma(signal, -depth, signal);
    scale_coefficient -= scale_coefficient_addend;
    float coefficient_depth = depth * TRANSPARENCY_WAVELET_COEFFICIENT_COUNT;
    int index_b = clamp(int(floor(coefficient_depth)), 0, TRANSPARENCY_WAVELET_COEFFICIENT_COUNT - 1);
    bool sample_a = index_b >= 1;
    int index_a = sample_a ? (index_b - 1) : index_b;
    index_b += TRANSPARENCY_WAVELET_COEFFICIENT_COUNT - 1;
    index_a += TRANSPARENCY_WAVELET_COEFFICIENT_COUNT - 1;
    float b = scale_coefficient;
    float a = sample_a ? scale_coefficient : 0;
    for (int i = 0; i < (TRANSPARENCY_WAVELET_RANK + 1); ++i) {
        int power = TRANSPARENCY_WAVELET_RANK - i;
        int new_index_b = (index_b - 1) >> 1;
        int wavelet_sign_b = ((index_b & 1) << 1) - 1;
        float coeff_b = get_coefficients(c, new_index_b);
        float wavelet_phase_b = ((index_b + 1) & 1) * exp2(-power);
        float k = float((new_index_b + 1) & ((1 << power) - 1));
        float addend = fma(fma(-exp2(-power), k, depth), float(wavelet_sign_b), wavelet_phase_b) * exp2(power * 0.5) * signal;
        coeff_b -= addend;
        b -= exp2(float(power) * 0.5) * coeff_b * float(wavelet_sign_b);
        index_b = new_index_b;
        if (sample_a) {
            int new_index_a = (index_a - 1) >> 1;
            int wavelet_sign_a = ((index_a & 1) << 1) - 1;
            float coeff_a = (new_index_a == new_index_b) ? coeff_b : get_coefficients(c, new_index_a);
            a -= exp2(float(power) * 0.5) * coeff_a * float(wavelet_sign_a);
            index_a = new_index_a;
        }
    }
    float t = coefficient_depth >= TRANSPARENCY_WAVELET_COEFFICIENT_COUNT ? 1.0 : fract(coefficient_depth);
    return mix(a, b, t);
}
float signal_corrected_transmittance(in vec4 c[4], float depth, float signal) {
    return clamp(exp(-signal_corrected_absorbance(c, depth, signal)), 0., 1.);
}
#endif

#ifndef _FLW_OIT_INSERT
#ifdef _FLW_DEPTH_RANGE
layout(location = 0) out vec4 _flw_depthRange_out;
#elif defined(_FLW_COLLECT_COEFFS)
layout(location = 0) out vec4 _flw_coeffs0;
layout(location = 1) out vec4 _flw_coeffs1;
layout(location = 2) out vec4 _flw_coeffs2;
layout(location = 3) out vec4 _flw_coeffs3;
#else
layout(location = 0) out vec4 _flw_accumulate;
#endif
#endif

void main() {
    float linearDepth = -v_in.misc.z;

    vec4 atlasTexel = flw_sampleAtlas(Sampler0, v_in.misc.xy);
    vec4 color = atlasTexel * v_in.color;
    color = mix(FogColor * vec4(1.0, 1.0, 1.0, color.a), color, v_in.misc.w);
    if (color.a < 0.1) {
        discard;
    }

#ifdef _FLW_OIT_INSERT
    color = apply_fog(color, v_in.fog.x, v_in.fog.y,
            FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
    _flw_mlabInsert(color, gl_FragCoord.z);
#elif defined(_FLW_DEPTH_RANGE)
    _flw_depthRange_out = vec4(-linearDepth + 1e-5, linearDepth + 1e-2, gl_FragCoord.z, 0.0);
#else
    color = apply_fog(color, v_in.fog.x, v_in.fog.y,
            FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    vec2 depthRange = texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0).rg;
    float delta = depthRange.x + depthRange.y;
    float our_depth = (linearDepth + depthRange.x) / delta;

    float our_transmittance = 1. - color.a;
    if (our_transmittance > 1e-5) {
        our_depth -= tented_blue_noise(our_depth) * _FLW_OIT_NOISE;
    }

  #ifdef _FLW_COLLECT_COEFFS
    vec4[4] result;
    result[0] = vec4(0.);
    result[1] = vec4(0.);
    result[2] = vec4(0.);
    result[3] = vec4(0.);
    add_transmittance(result, our_transmittance, our_depth);
    _flw_coeffs0 = result[0];
    _flw_coeffs1 = result[1];
    _flw_coeffs2 = result[2];
    _flw_coeffs3 = result[3];
  #else
    vec4 coeffTexels[4];
    _FLW_FETCH_COEFFS(coeffTexels);
    float transmittance = signal_corrected_transmittance(coeffTexels, our_depth, our_transmittance);
    _flw_accumulate = vec4(color.rgb * color.a, color.a) * transmittance;
  #endif
#endif
}
