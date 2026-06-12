#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

#include "flywheel:internal/oit_producer.glsl"

// Perf: OIT producers never write depth but do `discard` -- force the early depth test.
#ifndef _FLW_OIT_INSERT
layout(early_fragment_tests) in;
#endif

in vec4 vertexColor;
in vec2 texCoord0;
in vec2 lightCoord;
flat in ivec2 overlayCoord;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in float flw_oitViewZ; // eye-space Z (negative looking down -Z); eye-linear depth = -flw_oitViewZ
#ifdef _FLW_DEBUG
// (stable instance id, mesh base vertex) -- upstream common.vert's _flw_ids, emitted by the debug vertex variant.
flat in uvec2 _flw_ids;
#endif

#ifndef _FLW_DEPTH_RANGE

#ifdef _FLW_BINDLESS
flat in uint _flw_texIndex;
#define Sampler0 _flw_textures[nonuniformEXT(_flw_texIndex)]
#define Sampler1 _flw_textures[1]
#define Sampler2 _flw_textures[2]
#else
#ifdef _FLW_BINDLESS_GL
layout(std430, binding = 8) restrict readonly buffer _FlwTextureHandles {
    uvec2 _flw_textureHandles[];
};
flat in uint _flw_texIndex;
#define Sampler0 sampler2D(_flw_textureHandles[_flw_texIndex])
#else
uniform sampler2D Sampler0; // atlas
#endif
uniform sampler2D Sampler1; // overlay
uniform sampler2D Sampler2; // lightmap
#endif

#endif

void main() {
    float linearDepth = -flw_oitViewZ;

    #ifdef _FLW_DEPTH_RANGE

    _flw_writeDepthRange(linearDepth, gl_FragCoord.z);

    #else

    flw_vertexColor = vertexColor;
    flw_sampleColor = texture(Sampler0, texCoord0);
    flw_fragColor = flw_vertexColor * flw_sampleColor * ColorModulator;
    flw_fragLight = lightCoord;
    _flw_unpackMaterialProperties(_flw_packedMaterial.y, flw_material);
    flw_materialFragment();
    flw_shaderLight();

    // Per-material cutout (COEFFS/EVALUATE only); uber dispatches on the draw command's cutout index.
    #ifdef _FLW_UBER_FRAGMENT
    if (flw_discardPredicateUber(_flw_packedMaterial.x & 0xFFFFu, flw_fragColor)) {
        discard;
    }
    #elif defined(_FLW_USE_DISCARD)
    if (flw_discardPredicate(flw_fragColor)) {
        discard;
    }
    #endif

    float diffuseFactor = _flw_diffuseFactor();
    flw_fragColor.rgb *= diffuseFactor;
    if (flw_material.useOverlay) {
        vec4 overlay = texelFetch(Sampler1, overlayCoord, 0);
        flw_fragColor.rgb = mix(overlay.rgb, flw_fragColor.rgb, overlay.a);
    }
    vec4 lightColor = vec4(1.);
    if (flw_material.useLight) {
        lightColor = texture(Sampler2, clamp(flw_fragLight, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
        // Perf: lightmap alpha is 1 by construction; RGB-only lets COLLECT_COEFFS dead-code the chain.
        flw_fragColor.rgb *= lightColor.rgb;
    }

    #ifdef _FLW_DEBUG
    #if _FLW_DEBUG == 1
    flw_fragColor = vec4(flw_vertexNormal * .5 + .5, 1.);
    #elif _FLW_DEBUG == 2
    flw_fragColor = _flw_id2Color(_flw_ids.x);
    #elif _FLW_DEBUG == 3
    flw_fragColor = vec4(vec2((flw_fragLight * 15.0 + 0.5) / 16.), 0., 1.);
    #elif _FLW_DEBUG == 4
    flw_fragColor = lightColor;
    #elif _FLW_DEBUG == 5
    flw_fragColor = vec4(vec2(overlayCoord) / 16., 0., 1.);
    #elif _FLW_DEBUG == 6
    flw_fragColor = vec4(vec3(diffuseFactor), 1.);
    #elif _FLW_DEBUG == 7
    flw_fragColor = _flw_id2Color(_flw_ids.y);
    #endif
    #endif
    #ifdef _FLW_UBER_FRAGMENT
    vec4 color = flw_fogFilterUber(_flw_packedMaterial.x >> 16u, flw_fragColor, sphericalVertexDistance, cylindricalVertexDistance);
    #else
    vec4 color = flw_fogFilter(flw_fragColor, sphericalVertexDistance, cylindricalVertexDistance);
    #endif

    _flw_oitEmit(color, linearDepth);

    #endif
}
