// 26.2: wavelet.glsl injected by RenderPassShaders.assembleOitComposite (no #include in RenderPass sources).

layout(location = 0) out vec4 frag;

uniform sampler2D _flw_accumulate;
uniform sampler2D _flw_depthRange;
#ifdef _FLW_OIT_EMISSION
uniform sampler2D _flw_emission;
#endif
#ifdef _FLW_COEFF_ARRAY
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

void main() {
    vec4 texel = texelFetch(_flw_accumulate, ivec2(gl_FragCoord.xy), 0);

    if (texel.a < 1e-5) {
        discard;
    }

    vec4 coefficients[4];
    _FLW_FETCH_COEFFS(coefficients);
    float total = total_transmittance(coefficients);

    #ifdef _FLW_OIT_EMISSION
    // Premultiplied (ONE, ONE_MINUS_SRC_ALPHA): emission rounds to 8 bits in the same write as the layers, not after.
    frag = vec4(texel.rgb / texel.a * (1. - total) + texelFetch(_flw_emission, ivec2(gl_FragCoord.xy), 0).rgb,
            1. - total);
    #else
    frag = vec4(texel.rgb / texel.a, 1. - total);
    #endif
}
