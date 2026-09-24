layout(location = 0) in MeshVertexOut {
    vec2 texCoord;
#ifdef _FLW_MV_F16_VARYINGS
    f16vec4 color;
    f16vec4 light;
    f16vec3 normal;
#else
    vec4 color;
    vec4 light;
    vec3 normal;
#endif
    vec3 worldPos;
    flat uint fogCutout;
    flat uint packedMaterial; // packedMaterialProperties | overlay texel u4:u4 << 24 (see visual_main.mesh)
#ifdef _FLW_MV_CLIP
    vec2 clipData; // compact clip ABI (see visual_main.mesh); main copies into the prelude global
#endif
#if defined(_FLW_BINDLESS) || defined(_FLW_BINDLESS_GL)
    flat uint texIndex;
#endif
#ifdef _FLW_DEBUG
    flat uvec2 debugIds;
#endif
} v_in;

#include "flywheel:internal/oit_producer.glsl"

// Perf: discard disables the implicit early depth test; force it so opaque-occluded fragments skip shading.
#ifndef _FLW_OIT_INSERT
layout(early_fragment_tests) in;
#endif

#ifndef _FLW_DEPTH_RANGE

#ifdef _FLW_BINDLESS
#define Sampler0 _flw_textures[nonuniformEXT(v_in.texIndex)]
#define Sampler1 _flw_textures[1]
#define Sampler2 _flw_textures[2]
#else
#ifdef _FLW_BINDLESS_GL
layout(std430, binding = 8) restrict readonly buffer _FlwTextureHandles {
    uvec2 _flw_textureHandles[];
};
#define Sampler0 sampler2D(_flw_textureHandles[v_in.texIndex])
#else
uniform sampler2D Sampler0;
#endif
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
#endif

float _flw_meshDiffuse(uint cardinalMode, vec3 normal) {
    if (cardinalMode == FLW_MAT_CARDINAL_LIGHTING_MODE_ENTITY) {
        // 26.2: face-forward per fragment, mirroring vanilla PER_FACE_LIGHTING (see RenderPassShaders).
        return diffuseFromLightDirections(gl_FrontFacing ? normal : -normal);
    } else if (cardinalMode == FLW_MAT_CARDINAL_LIGHTING_MODE_CHUNK) {
        return flw_constantAmbientLight == 1u ? diffuseNether(normal) : diffuse(normal);
    }
    return 1.0;
}

#endif

void main() {
#ifdef _FLW_MV_F16_VARYINGS
    vec4 _mvColor = vec4(v_in.color);
    vec2 _mvLight = vec2(v_in.light.xy);
    vec3 _mvNormal = vec3(v_in.normal);
#else
    vec4 _mvColor = v_in.color;
    vec2 _mvLight = v_in.light.xy;
    vec3 _mvNormal = v_in.normal;
#endif
    vec4 viewPos4 = _flw_mvModelView * vec4(v_in.worldPos, 1.0);
    float linearDepth = -viewPos4.z;

    #ifdef _FLW_DEPTH_RANGE

    _flw_writeDepthRange(linearDepth, gl_FragCoord.z);

    #else

    ivec2 _mvOverlay = ivec2(int((v_in.packedMaterial >> 24u) & 0xFu), int(v_in.packedMaterial >> 28u));

    flw_vertexPos = vec4(v_in.worldPos, 1.0);
    flw_vertexNormal = normalize(_mvNormal);
    flw_vertexColor = _mvColor;
    flw_sampleColor = texture(Sampler0, v_in.texCoord);
    flw_fragColor = flw_sampleColor * flw_vertexColor * ColorModulator;
    flw_fragLight = _mvLight;
    _flw_unpackMaterialProperties(v_in.packedMaterial, flw_material);
    flw_materialFragment();

#ifdef _FLW_MV_CLIP
    _flw_clipData = v_in.clipData;
#endif
    if (flw_discardPredicateUber(v_in.fogCutout & 0xFFFFu, flw_fragColor)) {
        discard;
    }

    // Invariant: light shaders never write alpha (COLLECT_COEFFS reads alpha only).
    #ifndef _FLW_COLLECT_COEFFS
    flw_shaderLight();
    float diffuseFactor = _flw_meshDiffuse(flw_material.cardinalLightingMode, flw_vertexNormal);
    flw_fragColor.rgb *= diffuseFactor;
    if (flw_material.useOverlay) {
        vec4 overlay = texelFetch(Sampler1, _mvOverlay, 0);
        flw_fragColor.rgb = mix(overlay.rgb, flw_fragColor.rgb, overlay.a);
    }
    vec4 lightColor = vec4(1.);
    if (flw_material.useLight) {
        lightColor = texture(Sampler2, clamp(flw_fragLight + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
        flw_fragColor.rgb *= lightColor.rgb;
    }
    #endif

    #ifdef _FLW_DEBUG
    #ifdef _FLW_COLLECT_COEFFS
    flw_fragColor.a = 1.;
    #elif _FLW_DEBUG == 1
    flw_fragColor = vec4(flw_vertexNormal * .5 + .5, 1.);
    #elif _FLW_DEBUG == 2
    flw_fragColor = _flw_id2Color(v_in.debugIds.x);
    #elif _FLW_DEBUG == 3
    flw_fragColor = vec4(vec2((flw_fragLight * 15.0 + 0.5) / 16.), 0., 1.);
    #elif _FLW_DEBUG == 4
    flw_fragColor = lightColor;
    #elif _FLW_DEBUG == 5
    flw_fragColor = vec4(vec2(_mvOverlay) / 16., 0., 1.);
    #elif _FLW_DEBUG == 6
    flw_fragColor = vec4(vec3(diffuseFactor), 1.);
    #elif _FLW_DEBUG == 7
    flw_fragColor = _flw_id2Color(v_in.debugIds.y);
    #endif
    #endif
    vec2 fogDistance = vec2(v_in.light.zw);
    vec4 color = flw_fogFilterUber(v_in.fogCutout >> 16u, flw_fragColor, fogDistance.x, fogDistance.y);

    #if defined(_FLW_OIT_EMISSION) && defined(_FLW_OIT_INSERT)
    _flw_mlabInsertPremul(color.rgb * color.a, 0., gl_FragCoord.z);
    #elif defined(_FLW_OIT_INSERT)
    _flw_mlabInsertPremul(color.rgb * color.a, color.a, gl_FragCoord.z);
    #elif defined(_FLW_OIT_EMISSION)
    _flw_oitEmitAdditive(color.rgb * color.a, linearDepth);
    #else
    _flw_oitEmitPremul(color.rgb * color.a, color.a, linearDepth);
    #endif

    #endif
}
