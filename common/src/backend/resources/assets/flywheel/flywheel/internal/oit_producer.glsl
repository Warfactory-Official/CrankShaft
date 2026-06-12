#ifdef _FLW_DEPTH_RANGE

layout(location = 0) out vec4 _flw_depthRange_out;

void _flw_writeDepthRange(float linearDepth, float deviceDepth) {
    _flw_depthRange_out = vec4(-linearDepth + 1e-5, linearDepth + 1e-2, deviceDepth, 0.0);
}

#else

#ifdef _FLW_BINDLESS
layout(set = 1, binding = 0) uniform sampler2D _flw_textures[_FLW_BINDLESS_CAPACITY];
#endif

#ifndef _FLW_OIT_INSERT

#ifdef _FLW_OIT_LOCAL_READ
layout(input_attachment_index = 0, set = 0, binding = 14) uniform subpassInput _flw_depthRange;
#define _FLW_DEPTH_RANGE_FETCH() subpassLoad(_flw_depthRange)
#elif defined(_FLW_BINDLESS)
#define _FLW_DEPTH_RANGE_FETCH() texelFetch(_flw_textures[4], ivec2(gl_FragCoord.xy), 0)
#else
uniform sampler2D _flw_depthRange;
#define _FLW_DEPTH_RANGE_FETCH() texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0)
#endif

#ifdef _FLW_BINDLESS
#define _flw_blueNoise _flw_textures[3]
#else
uniform sampler2D _flw_blueNoise;
#endif

const float _FLW_OIT_NOISE = 0.07;

float tented_blue_noise(float normalizedDepth) {
    float tentIn = abs(normalizedDepth * 2. - 1);
    float tentIn2 = tentIn * tentIn;
    float tentIn4 = tentIn2 * tentIn2;
    float tent = 1 - (tentIn2 * tentIn4);

    float b = texture(_flw_blueNoise, gl_FragCoord.xy / vec2(64)).r;

    return b * tent;
}

#endif

#ifdef _FLW_COLLECT_COEFFS

layout(location = 0) out vec4 _flw_coeffs0;
layout(location = 1) out vec4 _flw_coeffs1;
layout(location = 2) out vec4 _flw_coeffs2;
layout(location = 3) out vec4 _flw_coeffs3;

#endif

#ifdef _FLW_EVALUATE

#ifdef _FLW_OIT_LOCAL_READ
layout(input_attachment_index = 1, set = 0, binding = 24) uniform subpassInput _flw_coefficients0;
layout(input_attachment_index = 2, set = 0, binding = 25) uniform subpassInput _flw_coefficients1;
layout(input_attachment_index = 3, set = 0, binding = 26) uniform subpassInput _flw_coefficients2;
layout(input_attachment_index = 4, set = 0, binding = 27) uniform subpassInput _flw_coefficients3;
#define _FLW_FETCH_COEFFS(dst) dst[0] = subpassLoad(_flw_coefficients0); \
    dst[1] = subpassLoad(_flw_coefficients1); \
    dst[2] = subpassLoad(_flw_coefficients2); \
    dst[3] = subpassLoad(_flw_coefficients3)
#elif defined(_FLW_BINDLESS)
#define _FLW_FETCH_COEFFS(dst) dst[0] = texelFetch(_flw_textures[5], ivec2(gl_FragCoord.xy), 0); \
    dst[1] = texelFetch(_flw_textures[6], ivec2(gl_FragCoord.xy), 0); \
    dst[2] = texelFetch(_flw_textures[7], ivec2(gl_FragCoord.xy), 0); \
    dst[3] = texelFetch(_flw_textures[8], ivec2(gl_FragCoord.xy), 0)
#elif defined(_FLW_COEFF_ARRAY)
layout(binding = 11) uniform sampler2DArray _flw_coefficients;
#define _FLW_FETCH_COEFFS(dst) dst[0] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 0), 0); \
    dst[1] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 1), 0); \
    dst[2] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 2), 0); \
    dst[3] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 3), 0)
#else
uniform sampler2D _flw_coefficients0;
uniform sampler2D _flw_coefficients1;
uniform sampler2D _flw_coefficients2;
uniform sampler2D _flw_coefficients3;
#define _FLW_FETCH_COEFFS(dst) dst[0] = texelFetch(_flw_coefficients0, ivec2(gl_FragCoord.xy), 0); \
    dst[1] = texelFetch(_flw_coefficients1, ivec2(gl_FragCoord.xy), 0); \
    dst[2] = texelFetch(_flw_coefficients2, ivec2(gl_FragCoord.xy), 0); \
    dst[3] = texelFetch(_flw_coefficients3, ivec2(gl_FragCoord.xy), 0)
#endif

layout(location = 0) out vec4 _flw_accumulate;

#endif

#ifndef _FLW_OIT_INSERT

void _flw_oitEmitPremul(vec3 premultiplied, float alpha, float linearDepth) {
    vec2 depthRange = _FLW_DEPTH_RANGE_FETCH().rg;
    float delta = depthRange.x + depthRange.y;
    float our_depth = (linearDepth + depthRange.x) / delta;

    float our_transmittance = 1. - alpha;
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

    #endif

    #ifdef _FLW_EVALUATE

    vec4 _flw_coeffTexels[4];
    _FLW_FETCH_COEFFS(_flw_coeffTexels);
    float transmittance = signal_corrected_transmittance(_flw_coeffTexels, our_depth, our_transmittance);

    _flw_accumulate = vec4(premultiplied, alpha) * transmittance;

    #endif
}

#endif

#ifdef _FLW_OIT_INSERT
#define _flw_oitEmit(color, linearDepth) _flw_mlabInsert(color, gl_FragCoord.z)
#else
void _flw_oitEmit(vec4 color, float linearDepth) {
    _flw_oitEmitPremul(color.rgb * color.a, color.a, linearDepth);
}
#endif

#endif
