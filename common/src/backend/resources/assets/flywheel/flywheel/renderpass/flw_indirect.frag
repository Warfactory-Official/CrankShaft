#ifdef _FLW_BINDLESS
layout(set = 1, binding = 0) uniform sampler2D _flw_textures[_FLW_BINDLESS_CAPACITY];
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

#ifdef _FLW_CRUMBLING
uniform sampler2D _flw_crumblingTex;
in vec2 _flw_crumblingTexCoord;
#endif

in vec4 vertexColor;
in vec2 texCoord0;
in vec2 lightCoord;
flat in ivec2 overlayCoord;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#ifdef _FLW_DEBUG
flat in uvec2 _flw_ids;
#endif

out vec4 fragColor;

void main() {
    flw_vertexColor = vertexColor;
    // Plain sample: the texel-snap/RGSS filter is TERRAIN-ONLY (dark sweep on entity samplers).

    flw_sampleColor = texture(Sampler0, texCoord0);
    flw_fragColor = flw_vertexColor * flw_sampleColor * ColorModulator;
    flw_fragLight = lightCoord;
    _flw_unpackMaterialProperties(_flw_packedMaterial.y, flw_material);

    // Per-material fragment hook (upstream common.frag order: after flw_fragColor, before crumbling/lighting).
    flw_materialFragment();

    #ifdef _FLW_CRUMBLING
    vec4 crumblingSampleColor = texture(_flw_crumblingTex, _flw_crumblingTexCoord);
    flw_fragColor.rgb = crumblingSampleColor.rgb;
    flw_fragColor.a *= crumblingSampleColor.a;
    #endif

    flw_shaderLight();

    // Per-material cutout (upstream order: after flw_shaderLight); uber dispatches on the draw command's cutout index.
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
        // Clamp the lightmap coord to the texel centres before the LINEAR sample -- the LUT bleeds at light extremes.
        lightColor = texture(Sampler2, clamp(flw_fragLight, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
        flw_fragColor *= lightColor;
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

    #ifdef _FLW_CRUMBLING
    // Crumbling: discard transparent cracks, skip fog (blend composites over the fogged block).
    if (flw_fragColor.a < 0.1) {
        discard;
    }
    fragColor = flw_fragColor;
    #elif defined(_FLW_UBER_FRAGMENT)
    fragColor = flw_fogFilterUber(_flw_packedMaterial.x >> 16u, flw_fragColor, sphericalVertexDistance, cylindricalVertexDistance);
    #else
    // Per-material fog spliced from material.fog().source() (LINEAR/NONE/LINEAR_FADE).
    fragColor = flw_fogFilter(flw_fragColor, sphericalVertexDistance, cylindricalVertexDistance);
    #endif
}
