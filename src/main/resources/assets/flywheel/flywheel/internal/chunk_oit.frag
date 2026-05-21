// chunk_oit.frag — 3 variants via _FLW_DEPTH_RANGE / _FLW_COLLECT_COEFFS / _FLW_EVALUATE define,
// matching the OitMode #defines used by common.frag. #version + mode #define prepended by
// ChunkOitPrograms.

#include "flywheel:internal/uniforms/frame.glsl"
#include "flywheel:internal/uniforms/fog.glsl"
#include "flywheel:internal/depth.glsl"
#include "flywheel:internal/wavelet.glsl"

in vec4 v_color;
in vec2 v_uv0;
in vec2 v_uv1;
in float v_fogDistance;

uniform sampler2D _flw_atlas;
uniform sampler2D _flw_lightmap;
uniform sampler2D _flw_depthRange;
uniform sampler2DArray _flw_coefficients;
uniform sampler2D _flw_blueNoise;

#ifdef _FLW_DEPTH_RANGE
out vec2 _flw_depthRange_out;
#endif

#ifdef _FLW_COLLECT_COEFFS
out vec4 _flw_coeffs0;
out vec4 _flw_coeffs1;
out vec4 _flw_coeffs2;
out vec4 _flw_coeffs3;
#endif

#ifdef _FLW_EVALUATE
out vec4 _flw_accumulate;
#endif

float tented_blue_noise(float normalizedDepth) {
    float tentIn = abs(normalizedDepth * 2. - 1);
    float tentIn2 = tentIn * tentIn;
    float tentIn4 = tentIn2 * tentIn2;
    float tent = 1 - (tentIn2 * tentIn4);
    float b = texture(_flw_blueNoise, gl_FragCoord.xy / vec2(64)).r;
    return b * tent;
}

void main() {
    vec4 atlas = texture(_flw_atlas, v_uv0);
    vec4 lightmap = texture(_flw_lightmap, v_uv1);
    vec4 color = atlas * v_color * lightmap;

    // Chunk renderer pre-sorts translucent quads; any fragment with near-zero alpha is invisible.
    if (color.a < 1e-3) discard;

    // Vanilla translucency is fogged in the fixed-function draw we suppressed; reapply it so water
    // etc. fade like the rest of the scene, matching the instanced path's flw_fogFilter.
    color.rgb = mix(color.rgb, flw_fogColor.rgb, flw_fogFactor(v_fogDistance) * flw_fogColor.a);

    float linearDepth = linearize_depth(gl_FragCoord.z, _flw_cullData.znear, _flw_cullData.zfar);

    #ifdef _FLW_DEPTH_RANGE
    // Mirror common.frag's unbalanced epsilon — bias toward "closer" in the noise window.
    _flw_depthRange_out = vec2(-linearDepth + 1e-5, linearDepth + 1e-2);
    #else
    vec2 depthRange = texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0).rg;
    float delta = depthRange.x + depthRange.y;
    float our_depth = (linearDepth + depthRange.x) / delta;
    float depth_adjustment = tented_blue_noise(our_depth) * _flw_oitNoise;
    float our_transmittance = 1. - color.a;
    if (our_transmittance > 1e-5) {
        our_depth -= depth_adjustment;
    }
    #endif

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
    float transmittance = signal_corrected_transmittance(_flw_coefficients, our_depth, our_transmittance);
    _flw_accumulate = vec4(color.rgb * color.a, color.a) * transmittance;
    #endif
}
