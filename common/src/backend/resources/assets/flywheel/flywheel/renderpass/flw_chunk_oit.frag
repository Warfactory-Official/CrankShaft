#moj_import <minecraft:fog.glsl>

#include "flywheel:internal/oit_producer.glsl"

// Perf: discard-only, no depth write -- force the early depth test (inserts: mlab.glsl).
#ifndef _FLW_OIT_INSERT
layout(early_fragment_tests) in;
#endif

// _FLW_DEPTH_RANGE_LITE (mesh producers): 3-scalar interface instead of the full 9.
#ifdef _FLW_DEPTH_RANGE_LITE

in float flw_vertexAlpha;
in vec2 texCoord0;
in float flw_oitViewZ; // eye-space Z; eye-linear depth = -flw_oitViewZ

#else

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in float flw_oitViewZ; // eye-space Z; eye-linear depth = -flw_oitViewZ
in float flw_chunkVisibility; // per-section chunk-load fade (was the chunksection.glsl ChunkVisibility uniform)

#endif

uniform sampler2D Sampler0; // atlas (also needed by DEPTH_RANGE for the cutout test)

void main() {
    float linearDepth = -flw_oitViewZ;

    #ifdef _FLW_DEPTH_RANGE_LITE

    if (flw_sampleAtlas(Sampler0, texCoord0).a * flw_vertexAlpha < 0.1) {
        discard;
    }

    #else

    vec4 color = flw_sampleAtlas(Sampler0, texCoord0) * vertexColor;
    color = mix(FogColor * vec4(1.0, 1.0, 1.0, color.a), color, flw_chunkVisibility);
    if (color.a < 0.1) {
        discard;
    }

    #endif

    #ifdef _FLW_DEPTH_RANGE

    _flw_writeDepthRange(linearDepth, gl_FragCoord.z);

    #else

    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    _flw_oitEmit(color, linearDepth);

    #endif
}
