#include <minecraft:projection.glsl>

float _flw_nativeLinearDepth(float deviceDepth) {
    #ifndef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    deviceDepth = (deviceDepth - 0.5) * 2.0;
    #endif
    return ProjMat[3][2] / (deviceDepth + ProjMat[2][2]);
}

#ifdef _FLW_EVALUATE

#ifdef _FLW_OIT_LOCAL_READ
layout(input_attachment_index = 0, set = 0, binding = 14) uniform subpassInput _flw_depthRange;
layout(input_attachment_index = 1, set = 0, binding = 24) uniform subpassInput _flw_coefficients0;
layout(input_attachment_index = 2, set = 0, binding = 25) uniform subpassInput _flw_coefficients1;
layout(input_attachment_index = 3, set = 0, binding = 26) uniform subpassInput _flw_coefficients2;
layout(input_attachment_index = 4, set = 0, binding = 27) uniform subpassInput _flw_coefficients3;
#define _FLW_NATIVE_DEPTH() subpassLoad(_flw_depthRange)
#define _FLW_NATIVE_COEFFICIENT(index) subpassLoad(_flw_coefficients##index)
#else
uniform sampler2D _flw_depthRange;
#define _FLW_NATIVE_DEPTH() texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0)
#ifdef _FLW_COEFF_ARRAY
#if __VERSION__ >= 420
layout(binding = 11) uniform sampler2DArray _flw_coefficients;
#else
uniform sampler2DArray _flw_coefficients;
#endif
#define _FLW_NATIVE_COEFFICIENT(index) texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, index), 0)
#else
uniform sampler2D _flw_coefficients0;
uniform sampler2D _flw_coefficients1;
uniform sampler2D _flw_coefficients2;
uniform sampler2D _flw_coefficients3;
#define _FLW_NATIVE_COEFFICIENT(index) texelFetch(_flw_coefficients##index, ivec2(gl_FragCoord.xy), 0)
#endif
#endif
uniform sampler2D _flw_blueNoise;

float _flw_nativeTransmittance(float alpha) {
    vec2 depthRange = _FLW_NATIVE_DEPTH().rg;
    float depth = (_flw_nativeLinearDepth(gl_FragCoord.z) + depthRange.x) / (depthRange.x + depthRange.y);
    float ownTransmittance = 1.0 - alpha;
    #ifdef _FLW_OIT_EMISSION
    float noiseDepth = clamp(depth, 0.0, 1.0);
    #else
    float noiseDepth = depth;
    #endif
    float tent = abs(noiseDepth * 2.0 - 1.0);
    float tent2 = tent * tent;
    float tent4 = tent2 * tent2;
    float noise = texture(_flw_blueNoise, gl_FragCoord.xy / vec2(64)).r * (1.0 - tent2 * tent4);
    #ifdef _FLW_OIT_EMISSION
    depth = max(depth - noise * 0.07, 0.0);
    #else
    if (ownTransmittance > 1e-5) depth -= noise * 0.07;
    #endif
    vec4 coefficients[4];
    coefficients[0] = _FLW_NATIVE_COEFFICIENT(0);
    coefficients[1] = _FLW_NATIVE_COEFFICIENT(1);
    coefficients[2] = _FLW_NATIVE_COEFFICIENT(2);
    coefficients[3] = _FLW_NATIVE_COEFFICIENT(3);
    #ifdef _FLW_OIT_EMISSION
    return transmittance(coefficients, depth);
    #else
    return signal_corrected_transmittance(coefficients, depth, ownTransmittance);
    #endif
}

#endif

void executeAlphaOnlyPhase(float deviceDepth, float alpha) {
    #ifdef OIT_ADDITIVE
    discard;
    #elif defined(_FLW_DEPTH_RANGE)
    if (alpha < 0.01) discard;
    _flw_writeDepthRange(_flw_nativeLinearDepth(deviceDepth), deviceDepth);
    // Native cloud depth preparation also consumes the nearest effectively opaque OIT surface.
    _flw_depthRange_out.a = alpha > 0.99 ? deviceDepth : 0.0;
    #elif defined(_FLW_COLLECT_COEFFS)
    _flw_oitEmitPremul(vec3(0.0), alpha, _flw_nativeLinearDepth(deviceDepth));
    #endif
}

#ifdef OIT_ACCUMULATE
vec4 sampleColorForAccumulation(vec4 color) {
    #if defined(RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE) && !defined(_FLW_OIT_INSERT)
    gl_FragDepth = gl_FragCoord.z;
    #endif
    #ifdef OIT_ADDITIVE
    float alpha = 0.0;
    #else
    float alpha = color.a;
    #endif
    vec4 premultiplied = vec4(color.rgb * color.a, alpha);
    #ifdef _FLW_OIT_INSERT
    return premultiplied;
    #else
    return premultiplied * _flw_nativeTransmittance(color.a);
    #endif
}
#endif
