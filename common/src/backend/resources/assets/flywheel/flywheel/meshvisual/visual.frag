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

#ifdef _FLW_BINDLESS
layout(set = 1, binding = 0) uniform sampler2D _flw_textures[_FLW_BINDLESS_CAPACITY];
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

out vec4 fragColor;

#ifdef _FLW_CRUMBLING
uniform sampler2D _flw_crumblingTex;

const int _FLW_FACE_DOWN = 0;
const int _FLW_FACE_UP = 1;
const int _FLW_FACE_NORTH = 2;
const int _FLW_FACE_SOUTH = 3;
const int _FLW_FACE_WEST = 4;
const int _FLW_FACE_EAST = 5;

int _flw_getNearestFacing(vec3 n) {
    float m = -2.0;
    int face = _FLW_FACE_NORTH;
    if (-n.y > m) { m = -n.y; face = _FLW_FACE_DOWN; }
    if (n.y > m)  { m = n.y;  face = _FLW_FACE_UP; }
    if (-n.z > m) { m = -n.z; face = _FLW_FACE_NORTH; }
    if (n.z > m)  { m = n.z;  face = _FLW_FACE_SOUTH; }
    if (-n.x > m) { m = -n.x; face = _FLW_FACE_WEST; }
    if (n.x > m)  { m = n.x;  face = _FLW_FACE_EAST; }
    return face;
}

vec2 _flw_crumblingTexCoord(vec3 p, vec3 n) {
    switch (_flw_getNearestFacing(n)) {
        case _FLW_FACE_DOWN:  return vec2(p.x, -p.z);
        case _FLW_FACE_UP:    return vec2(p.x, p.z);
        case _FLW_FACE_NORTH: return vec2(-p.x, -p.y);
        case _FLW_FACE_SOUTH: return vec2(p.x, -p.y);
        case _FLW_FACE_WEST:  return vec2(-p.z, -p.y);
        case _FLW_FACE_EAST:  return vec2(p.z, -p.y);
    }
    return vec2(-p.x, -p.y);
}
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

    FlwMaterial material;
    _flw_unpackMaterialProperties(v_in.packedMaterial, material);

    // Material hook: after the modulate, before crumbling/cutout; nametag.frag et al. may rewrite flw_fragColor.
    flw_vertexColor = _mvColor;
    flw_sampleColor = texture(Sampler0, v_in.texCoord);
    flw_fragColor = flw_sampleColor * flw_vertexColor * ColorModulator;
    flw_materialFragment();
    vec4 color = flw_fragColor;
#ifdef _FLW_CRUMBLING
    vec4 crack = texture(_flw_crumblingTex, _flw_crumblingTexCoord(_mvWorldPos, normalize(_mvNormal)));
    color.rgb = crack.rgb;
    color.a *= crack.a;
    if (color.a < 0.1) {
        discard;
    }
#else
#ifdef _FLW_MV_CLIP
    _flw_clipData = v_in.clipData;
#endif
    if (flw_discardPredicateUber(v_in.overlayCutout >> 16u, color)) {
        discard;
    }
#endif

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

#ifdef _FLW_CRUMBLING
    fragColor = color;
#else
    vec3 viewPos = (_flw_mvModelView * vec4(_mvWorldPos, 1.0)).xyz;
    float fogSpherical = length(viewPos);
    float fogCylindrical = max(length(viewPos.xz), abs(viewPos.y));
    fragColor = apply_fog(color, fogSpherical, fogCylindrical, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
#endif
}
