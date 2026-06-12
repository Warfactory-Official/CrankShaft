#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

#include "flywheel:internal/oit_producer.glsl"

// Perf: OIT producers never write depth but do `discard` -- force the early depth test.
#ifndef _FLW_OIT_INSERT
layout(early_fragment_tests) in;
#endif

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in float flw_oitViewZ; // eye-space Z; eye-linear depth = -flw_oitViewZ

uniform sampler2D Sampler0; // rain/snow texture (also read by DEPTH_RANGE for the cutout test)

void main() {
    float linearDepth = -flw_oitViewZ;

    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }

    #ifdef _FLW_DEPTH_RANGE

    _flw_writeDepthRange(linearDepth, gl_FragCoord.z);

    #else

    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    _flw_oitEmit(color, linearDepth);

    #endif
}
