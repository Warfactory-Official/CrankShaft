layout(location = 0) in MeshVertexOut {
    vec2 texCoord;
#ifdef _FLW_MV_F16_VARYINGS
    f16vec4 color;
    f16vec2 light;
    f16vec3 normal;
#else
    vec4 color;
    vec2 light;
    vec3 normal;
#endif
    vec3 worldPos;
    flat uint overlayCutout; // overlay texel u8:u8 | cutout index << 16 (see visual_main.mesh)
    flat uint packedMaterial;
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
    vec2 _mvLight = vec2(v_in.light);
    vec3 _mvNormal = vec3(v_in.normal);
#else
    vec4 _mvColor = v_in.color;
    vec2 _mvLight = v_in.light;
    vec3 _mvNormal = v_in.normal;
#endif
    vec3 _mvWorldPos = v_in.worldPos;
    ivec2 _mvOverlay = ivec2(int(v_in.overlayCutout & 0xFFu), int((v_in.overlayCutout >> 8u) & 0xFFu));

    vec4 viewPos4 = _flw_mvModelView * vec4(_mvWorldPos, 1.0);
    float linearDepth = -viewPos4.z;

    #ifdef _FLW_DEPTH_RANGE

    _flw_writeDepthRange(linearDepth, gl_FragCoord.z);

    #else

    flw_vertexColor = _mvColor;
    flw_sampleColor = texture(Sampler0, v_in.texCoord);
    flw_fragColor = flw_sampleColor * flw_vertexColor * ColorModulator;
    flw_materialFragment();
    float alpha = flw_fragColor.a;

#ifdef _FLW_MV_CLIP
    _flw_clipData = v_in.clipData;
#endif
    if (flw_discardPredicateUber(v_in.overlayCutout >> 16u, flw_fragColor)) {
        discard;
    }

    #ifdef _FLW_DEBUG
    alpha = 1.0;
    #endif

    #ifdef _FLW_COLLECT_COEFFS
    _flw_oitEmitPremul(vec3(0.), alpha, linearDepth);
    #endif

    #if defined(_FLW_EVALUATE) || defined(_FLW_OIT_INSERT)
    FlwMaterial material;
    _flw_unpackMaterialProperties(v_in.packedMaterial, material);

    vec4 color = flw_fragColor;

    vec3 normal = normalize(_mvNormal);
    vec2 lightCoord = _mvLight;
    if (material.useLight) {
        FlwLightAo lightAo;
        if (flw_light(_mvWorldPos, normal, lightAo)) {
            color.rgb *= lightAo.ao;
            lightCoord = lightAo.light;
        }
    }
    float diffuseFactor = _flw_meshDiffuse(material.cardinalLightingMode, normal);
    color.rgb *= diffuseFactor;
    if (material.useOverlay) {
        vec4 overlay = texelFetch(Sampler1, _mvOverlay, 0);
        color.rgb = mix(overlay.rgb, color.rgb, overlay.a);
    }
    vec4 lightColor = vec4(1.);
    if (material.useLight) {
        lightColor = texture(Sampler2, clamp(lightCoord, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
        color *= lightColor;
    }

    #ifdef _FLW_DEBUG
    #if _FLW_DEBUG == 1
    color = vec4(normal * .5 + .5, 1.);
    #elif _FLW_DEBUG == 2
    color = _flw_id2Color(v_in.debugIds.x);
    #elif _FLW_DEBUG == 3
    color = vec4(vec2((lightCoord * 15.0 + 0.5) / 16.), 0., 1.);
    #elif _FLW_DEBUG == 4
    color = lightColor;
    #elif _FLW_DEBUG == 5
    color = vec4(vec2(_mvOverlay) / 16., 0., 1.);
    #elif _FLW_DEBUG == 6
    color = vec4(vec3(diffuseFactor), 1.);
    #elif _FLW_DEBUG == 7
    color = _flw_id2Color(v_in.debugIds.y);
    #endif
    #endif
    vec3 viewPos = viewPos4.xyz;
    color = apply_fog(color, length(viewPos), max(length(viewPos.xz), abs(viewPos.y)), FogEnvironmentalStart,
            FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    #ifdef _FLW_OIT_INSERT
    _flw_mlabInsertPremul(color.rgb * alpha, alpha, gl_FragCoord.z);
    #else
    _flw_oitEmitPremul(color.rgb * alpha, alpha, linearDepth);
    #endif
    #endif

    #endif
}
