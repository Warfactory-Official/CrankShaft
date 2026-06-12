#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

#include "flywheel:internal/oit_producer.glsl"

// Perf: discard-only, no depth write -- force the early depth test (inserts: mlab.glsl).
#ifndef _FLW_OIT_INSERT
layout(early_fragment_tests) in;
#endif

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#ifdef _FLW_BER_PER_FACE
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif
#ifndef _FLW_BER_EMISSIVE
in vec4 lightMapColor;
#endif
in vec4 overlayColor;
in vec2 texCoord0;
in float flw_oitViewZ; // eye-space Z; eye-linear depth = -flw_oitViewZ

uniform sampler2D Sampler0; // atlas (also read by DEPTH_RANGE for the cutout test)

void main() {
    float linearDepth = -flw_oitViewZ;

    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
#ifdef _FLW_BER_PER_FACE
    vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
    vec4 faceVertexColor = vertexColor;
#endif
    color *= faceVertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#ifndef _FLW_BER_EMISSIVE
    color *= lightMapColor;
#endif

    #ifdef _FLW_DEPTH_RANGE

    _flw_writeDepthRange(linearDepth, gl_FragCoord.z);

    #else

    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    _flw_oitEmit(color, linearDepth);

    #endif
}
