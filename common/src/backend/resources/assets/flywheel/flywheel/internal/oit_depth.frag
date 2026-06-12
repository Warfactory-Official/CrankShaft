// 26.2: wavelet.glsl injected by RenderPassShaders.assembleOitDepth (no #include in RenderPass sources).

#ifdef _FLW_OIT_LOCAL_READ
layout(input_attachment_index = 0, set = 0, binding = 14) uniform subpassInput _flw_depthRange;
layout(input_attachment_index = 1, set = 0, binding = 24) uniform subpassInput _flw_coefficients0;
layout(input_attachment_index = 2, set = 0, binding = 25) uniform subpassInput _flw_coefficients1;
layout(input_attachment_index = 3, set = 0, binding = 26) uniform subpassInput _flw_coefficients2;
layout(input_attachment_index = 4, set = 0, binding = 27) uniform subpassInput _flw_coefficients3;
#define _FLW_DEPTH_RANGE_FETCH() subpassLoad(_flw_depthRange)
#define _FLW_FETCH_COEFFS(dst) dst[0] = subpassLoad(_flw_coefficients0); \
    dst[1] = subpassLoad(_flw_coefficients1); \
    dst[2] = subpassLoad(_flw_coefficients2); \
    dst[3] = subpassLoad(_flw_coefficients3)
#elif defined(_FLW_COEFF_ARRAY)
uniform sampler2D _flw_depthRange;
layout(binding = 11) uniform sampler2DArray _flw_coefficients;
#define _FLW_DEPTH_RANGE_FETCH() texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0)
#define _FLW_FETCH_COEFFS(dst) dst[0] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 0), 0); \
    dst[1] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 1), 0); \
    dst[2] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 2), 0); \
    dst[3] = texelFetch(_flw_coefficients, ivec3(gl_FragCoord.xy, 3), 0)
#else
uniform sampler2D _flw_depthRange;
uniform sampler2D _flw_coefficients0;
uniform sampler2D _flw_coefficients1;
uniform sampler2D _flw_coefficients2;
uniform sampler2D _flw_coefficients3;
#define _FLW_DEPTH_RANGE_FETCH() texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0)
#define _FLW_FETCH_COEFFS(dst) dst[0] = texelFetch(_flw_coefficients0, ivec2(gl_FragCoord.xy), 0); \
    dst[1] = texelFetch(_flw_coefficients1, ivec2(gl_FragCoord.xy), 0); \
    dst[2] = texelFetch(_flw_coefficients2, ivec2(gl_FragCoord.xy), 0); \
    dst[3] = texelFetch(_flw_coefficients3, ivec2(gl_FragCoord.xy), 0)
#endif

void main() {
    vec4 coefficients[4];
    _FLW_FETCH_COEFFS(coefficients);
    // 26.2: gate on total transmittance; discarding non-covered pixels preserves opaque depth.
    float transmittance_at_far_depth = total_transmittance(coefficients);
    if (transmittance_at_far_depth > 0.0001) {
        discard;
    }

    // 26.2: ProjMat is vertex-stage-only; fullscreen frag reads reversed-Z device depth from depthRange.b.
    gl_FragDepth = _FLW_DEPTH_RANGE_FETCH().b;
}
