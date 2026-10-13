#include "flywheel:internal/oit_producer.glsl"

uniform sampler2D _flw_layerColor;
uniform sampler2D _flw_layerDepth;

#ifdef _FLW_OIT_INSERT

// The MLAB insert is a side effect the fixed-function LATE depth test cannot undo, so occlusion runs in-shader.
uniform sampler2D _flw_opaqueDepth;

#endif

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec4 layer = texelFetch(_flw_layerColor, px, 0);
    if (layer.a < 0.001) {
        discard;
    }

    float deviceDepth = max(texelFetch(_flw_layerDepth, px, 0).r, 1e-9);

    #ifdef _FLW_OIT_INSERT

    if (deviceDepth < texelFetch(_flw_opaqueDepth, px, 0).r) {
        discard;
    }
    _flw_mlabInsertPremul(layer.rgb, layer.a, deviceDepth);

    #else

    gl_FragDepth = deviceDepth;

    // 26.2: infinite reversed-Z, znear 0.05: device = znear / eyeLinear.
    float linearDepth = 0.05 / deviceDepth;

    #ifdef _FLW_DEPTH_RANGE

    _flw_writeDepthRange(linearDepth, deviceDepth);

    #else

    _flw_oitEmitPremul(layer.rgb, layer.a, linearDepth);

    #endif

    #endif
}
