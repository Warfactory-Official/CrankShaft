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

layout(location = 0) out vec4 fragColor;
#ifdef _FLW_FAR_RAW_OIT
#ifdef _FLW_OIT_MLAB
layout(pixel_interlock_unordered) in;
#define _FLW_MLAB_LAYOUT_DECLARED
#endif
in vec3 _flw_farPosition;
void _flw_rawBegin();
void _flw_rawEmit(vec4 color, float linearDepth, float reverseDeviceDepth);
float _flw_rawDeviceDepth(float depth);
float _flw_rawLinearDepth(float depth);
bool _flw_farRawKeep(vec3 position, float depth);
#endif
#ifdef _FLW_FAR_IRIS_CAPTURE
uniform uint _flw_farIrisFirstPrimitive;
uniform uint _flw_farIrisPrimitiveCount;
uniform uint _flw_farIrisMeshPrimitiveCount;
void _flw_farCaptureIris(vec4 albedo, vec3 normal, vec4 tangent, vec2 light, float diffuse, float unlit,
                         uvec4 material, vec3 world);
#endif
#if defined(_FLW_FAR_BAKE) || defined(_FLW_FAR_SURFACE)
in vec3 _flw_farPosition;
flat in uvec2 _flw_farMaterialKey;
layout(location = 1) out vec4 _flw_farNormal;
layout(location = 3) out uvec4 _flw_farMaterial;

vec4 _flw_farTangent(vec3 normal) {
    vec3 dx = dFdx(_flw_farPosition);
    vec3 dy = dFdy(_flw_farPosition);
    vec2 uvDx = dFdx(texCoord0);
    vec2 uvDy = dFdy(texCoord0);
    float determinant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
    // Untextured meshes have a constant UV: orient their tangent basis from the geometric normal.
    if (determinant == 0.0) {
        vec3 axis = abs(normal.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        return vec4(normalize(cross(axis, normal)), 1.0);
    }
    vec3 tangent = (dx * uvDy.y - dy * uvDx.y) / determinant;
    vec3 bitangent = (dy * uvDx.x - dx * uvDy.x) / determinant;
    tangent = normalize(tangent - normal * dot(normal, tangent));
    return vec4(tangent, dot(cross(normal, tangent), bitangent) < 0.0 ? -1.0 : 1.0);
}
#endif
#ifdef _FLW_FAR_SURFACE
layout(location = 2) out vec4 _flw_farLight;
#endif

void main() {
    #if defined(_FLW_FAR_BAKE) || defined(_FLW_FAR_SURFACE)
    vec4 tangent = _flw_farTangent(normalize(flw_vertexNormal));
    #endif
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
    #if defined(_FLW_FAR_BAKE) || defined(_FLW_FAR_SURFACE)
    if (flw_material.useOverlay) {
        vec4 overlay = texelFetch(Sampler1, overlayCoord, 0);
        flw_fragColor.rgb = mix(overlay.rgb, flw_fragColor.rgb, overlay.a);
    }
    vec3 normal = normalize(flw_vertexNormal);
    _flw_farNormal = vec4(normal * 0.5 + 0.5, diffuseFactor);
    uint tangentFlags = (_flw_farMaterialKey.y << 1u | uint(tangent.w < 0.0)) << 24u;
    _flw_farMaterial = uvec4(floatBitsToUint(texCoord0), _flw_farMaterialKey.x,
            (packSnorm4x8(vec4(tangent.xyz, 0.0)) & 0xFFFFFFu) | tangentFlags);
    #ifdef _FLW_FAR_LAYER_CAPTURE
    _flw_farCaptureLayer(flw_fragColor, normal, tangent, flw_fragLight, diffuseFactor,
            flw_material.useLight ? 0.0 : 1.0, _flw_farMaterial);
    #elif defined(_FLW_FAR_IRIS_CAPTURE)
    uint primitive = uint(gl_PrimitiveID) % _flw_farIrisMeshPrimitiveCount;
    if (primitive < _flw_farIrisFirstPrimitive
            || primitive - _flw_farIrisFirstPrimitive >= _flw_farIrisPrimitiveCount) discard;
    _flw_farCaptureIris(flw_fragColor, normal, tangent, flw_fragLight, diffuseFactor,
            flw_material.useLight ? 0.0 : 1.0, _flw_farMaterial, _flw_farPosition);
    #elif defined(_FLW_FAR_BAKE)
    // Alpha keeps the unlit fraction separately from coverage, supplied by the bake's depth.
    fragColor = vec4(flw_fragColor.rgb, flw_material.useLight ? 0.0 : 1.0);
    #else
    fragColor = vec4(flw_fragColor.rgb, 1.0);
    _flw_farLight = vec4(flw_fragLight, flw_material.useLight ? 0.0 : 1.0, 1.0);
    #endif
    return;
    #endif
    flw_fragColor.rgb *= diffuseFactor;

    if (flw_material.useOverlay) {
        vec4 overlay = texelFetch(Sampler1, overlayCoord, 0);
        flw_fragColor.rgb = mix(overlay.rgb, flw_fragColor.rgb, overlay.a);
    }
    vec4 lightColor = vec4(1.);
    if (flw_material.useLight) {
        // 26.2 sample_lightmap: half-texel offset, clamped to the texel centres (LINEAR sampler).
        lightColor = texture(Sampler2, clamp(flw_fragLight + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
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
    #ifdef _FLW_FAR_RAW_OIT
    if (!_flw_farRawKeep(_flw_farPosition, gl_FragCoord.z)) discard;
    vec4 shadedColor = fragColor;
    _flw_rawBegin();
    _flw_rawLock();
    _flw_rawEmit(shadedColor, _flw_rawLinearDepth(gl_FragCoord.z), _flw_rawDeviceDepth(gl_FragCoord.z));
    _flw_rawUnlock();
    #endif
}
