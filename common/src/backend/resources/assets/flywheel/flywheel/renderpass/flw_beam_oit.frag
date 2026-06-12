#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

#include "flywheel:internal/oit_producer.glsl"

// Beam has no cutout/discard; explicit early test for parity (inserts get it from mlab.glsl).
#ifndef _FLW_OIT_INSERT
layout(early_fragment_tests) in;
#endif

in vec4 vertexColor;
in vec2 texCoord0;
in float flw_oitViewZ; // eye-space Z; eye-linear depth = -flw_oitViewZ

uniform sampler2D Sampler0; // the beam texture (REPEAT wrap -- bound per-draw from the captured RenderType)

void main() {
    float linearDepth = -flw_oitViewZ;

    vec4 color = texture(Sampler0, texCoord0);
    color *= vertexColor * ColorModulator;

    #ifdef _FLW_DEPTH_RANGE

    _flw_writeDepthRange(linearDepth, gl_FragCoord.z);

    #else

    float fragmentDistance = 1.0 / gl_FragCoord.w;
    color = apply_fog(color, fragmentDistance, fragmentDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    _flw_oitEmit(color, linearDepth);

    #endif
}
