#include "flywheel:internal/far/far.glsl"

layout(std430, binding = 12) restrict readonly buffer TileBuffer {
    FarTile _flw_farTiles[];
};

layout(binding = 13) uniform sampler2DArray _flw_farAtlasColor;
layout(binding = 14) uniform sampler2DArray _flw_farAtlasAux;
layout(binding = 15) uniform sampler2DArray _flw_farAtlasNormal;
#ifdef _FLW_FAR_SURFACE
layout(binding = 16) uniform usampler2DArray _flw_farAtlasMaterial;
#endif
uniform sampler2D Sampler2; // lightmap

flat in uint _flw_farTileSlot;
flat in vec3 _flw_farEye;
flat in mat4 _flw_farToClip;
flat in vec3 _flw_farShade;
flat in mat3 _flw_farRotation;
in vec3 _flw_farTexel;

#if __VERSION__ >= 420
layout(depth_less) out float gl_FragDepth;
#endif
layout(location = 0) out vec4 fragColor;
#ifdef _FLW_FAR_SURFACE
layout(location = 1) out vec4 _flw_farNormal;
layout(location = 2) out vec4 _flw_farLight;
layout(location = 3) out uvec4 _flw_farMaterial;
#endif

// Bounds a ray's walk; a ray still outside the hull then is dropped.
#define _FLW_FAR_MARCH_ITERATIONS 256

FarTile _flw_farTile;
int _flw_farLod;

// The face's depth map under texel point q (nearest texel of the level), and q's depth from the face.
float _flw_farMap(uint face, vec3 q, out float depth) {
    ivec3 rect;
    ivec2 size;
    vec2 texel = _flw_farFaceTexel(_flw_farTile, face, q, depth, rect, size);
    ivec2 lo = rect.xy >> _flw_farLod;
    ivec2 at = clamp(ivec2(floor(texel)) >> _flw_farLod, lo, lo + (size >> _flw_farLod) - 1);
    return texelFetch(_flw_farAtlasAux, ivec3(at, rect.z), _flw_farLod).r;
}

#ifdef _FLW_FAR_SURFACE
// Material records stay discrete while colour filters across covered and empty texels at the silhouette.
ivec3 _flw_farMaterialTexel(vec2 at, ivec3 rect, ivec2 size) {
    vec2 point = at / float(1 << _flw_farLod) - 0.5;
    ivec2 base = ivec2(floor(point));
    vec2 fraction = fract(point);
    ivec2 lo = rect.xy >> _flw_farLod;
    ivec2 hi = ((rect.xy + size) >> _flw_farLod) - 1;
    ivec3 selected = ivec3(clamp(base, lo, hi), rect.z);
    float largest = -1.0;
    for (int child = 0; child < 4; child++) {
        ivec2 offset = ivec2(child & 1, child >> 1);
        ivec3 texel = ivec3(clamp(base + offset, lo, hi), rect.z);
        vec2 weight = mix(1.0 - fraction, fraction, vec2(offset));
        float contribution = texelFetch(_flw_farAtlasColor, texel, _flw_farLod).a * weight.x * weight.y;
        if (contribution > largest) {
            largest = contribution;
            selected = texel;
        }
    }
    return selected;
}
#endif

void _flw_farWriteOpaque(vec3 q, vec4 color, vec4 normal, float unlit, uvec4 material) {
    if (color.a == 0.0) discard;
    #ifdef _FLW_FAR_SURFACE
    fragColor = vec4(color.rgb / color.a, 1.0);
    _flw_farNormal = vec4(normalize(_flw_farRotation * (normal.rgb * 2.0 - 1.0)) * 0.5 + 0.5, normal.a);
    _flw_farLight = vec4(_flw_farShade.xy, unlit, 1.0);
    vec4 tangent = unpackSnorm4x8(material.w);
    material.w = (packSnorm4x8(vec4(normalize(_flw_farRotation * tangent.xyz), 0.0)) & 0xFFFFFFu)
            | (material.w & 0xFF000000u);
    _flw_farMaterial = material;
    #else
    vec3 light = texture(Sampler2, clamp(_flw_farShade.xy + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
    fragColor = vec4(color.rgb / color.a * normal.a * mix(light, vec3(1.0), unlit), 1.0);
    #endif
    vec4 clip = _flw_farToClip * vec4(q, 1.0);
    gl_FragDepth = clip.z / clip.w;
}

void _flw_farPlanarOpaque(vec3 q, uint axis) {
    // This rectangle lies on a certified physical plane. Its facing bake retains cutouts and one-sided coverage;
    // the four edge-on captures contain no samples and cannot constrain its footprint.
    q[axis] = uintBitsToFloat(_flw_farTile.inset1.z);
    uint face = axis * 2u + uint(_flw_farEye[axis] < q[axis]);
    float depth;
    ivec3 rect;
    ivec2 size;
    vec2 texel = _flw_farFaceTexel(_flw_farTile, face, q, depth, rect, size);
    float margin = 0.5 * float(1 << _flw_farLod);
    vec2 at = clamp(texel, vec2(rect.xy) + margin, vec2(rect.xy + size) - margin);
    vec3 uv = vec3(at / float(_FLW_FAR_LAYER_SIZE), rect.z);
    vec4 color = textureLod(_flw_farAtlasColor, uv, float(_flw_farLod));
    vec4 normal = textureLod(_flw_farAtlasNormal, uv, float(_flw_farLod));
    float unlit = textureLod(_flw_farAtlasAux, uv, float(_flw_farLod)).g;
    uvec4 material = uvec4(0u);
    #ifdef _FLW_FAR_SURFACE
    ivec3 materialAt = _flw_farMaterialTexel(at, rect, size);
    material = texelFetch(_flw_farAtlasMaterial, materialAt, _flw_farLod);
    normal.rgb = texelFetch(_flw_farAtlasNormal, materialAt, _flw_farLod).rgb;
    #endif
    _flw_farWriteOpaque(q, color, normal, unlit, material);
}

// The ray from the box face away from the eye, walked over the levels of the 3 face views facing the eye (per texel
// the nearest covered depth from the face, conservative at coarser levels) to the first point behind the first content
// each view sees at the frame's level: inside the visual hull they carve. The hit takes the colour of the face whose
// view agrees best, lit by the part's light where the bake was lit.
void main() {
    _flw_farTile = _flw_farTiles[_flw_farTileSlot];
    _flw_farLod = int(_flw_farShade.z);
    if (_flw_farTile.extent.w != 0u) {
        _flw_farPlanarOpaque(_flw_farTexel, _flw_farTile.extent.w - 1u);
        return;
    }
    vec3 extent = vec3(_flw_farTile.extent.xyz);
    vec3 dir = normalize(_flw_farTexel - _flw_farEye);
    dir = mix(dir, vec3(1e-6), equal(dir, vec3(0.0)));
    uvec3 faces = uvec3(0u, 2u, 4u) + uvec3(greaterThan(dir, vec3(0.0)));
    vec3 reciprocal = 1.0 / dir;
    vec3 exits = max(-_flw_farTexel * reciprocal, (extent - _flw_farTexel) * reciprocal);
    float exit = max(min(exits.x, min(exits.y, exits.z)), 0.0);
    float bias = 0.5 * float(1 << _flw_farLod);

    // Per facing face, in texels along t: its view's texel start + slope * t and the depth from the face.
    vec2 start[3];
    vec2 slope[3];
    vec2 depth[3];
    ivec3 rect[3];
    ivec2 size[3];
    for (int i = 0; i < 3; i++) {
        int u = (i + 1) % 3;
        int v = (i + 2) % 3;
        bool negative = (faces[i] & 1u) != 0u;
        rect[i] = _flw_farPairRect(_flw_farTile, uint(i));
        size[i] = ivec2(_flw_farTile.extent[u], _flw_farTile.extent[v]);
        if (negative) {
            rect[i].x += size[i].x;
        }
        start[i] = vec2(negative ? extent[u] - _flw_farTexel[u] : _flw_farTexel[u], _flw_farTexel[v]);
        slope[i] = vec2(negative ? -dir[u] : dir[u], dir[v]);
        depth[i] = vec2(negative ? _flw_farTexel[i] : extent[i] - _flw_farTexel[i], abs(dir[i]));
    }

    float t = 0.0;
    int level = int(_FLW_FAR_MAX_LOD);
    bool hit = false;
    for (int n = 0; n < _FLW_FAR_MARCH_ITERATIONS && t <= exit; n++) {
        // A view whose texel at this level holds nothing nearer than the ray keeps it outside until it reaches that
        // depth or leaves the texel.
        float cell = float(1 << level);
        bool outside = false;
        float next = t;
        for (int i = 0; i < 3; i++) {
            vec2 at = start[i] + slope[i] * t;
            ivec2 texel = clamp(ivec2(floor(at / cell)), ivec2(0), (size[i] >> level) - 1);
            float map = texelFetch(_flw_farAtlasAux, ivec3((rect[i].xy >> level) + texel, rect[i].z), level).r
                    * extent[i];
            float d = depth[i].x + depth[i].y * t;
            if (d < map - bias) {
                outside = true;
                vec2 leave = ((vec2(texel) + step(0.0, slope[i])) * cell - at) / slope[i];
                next = max(next, t + min((map - bias - d) / depth[i].y, min(leave.x, leave.y)));
            }
        }
        if (outside) {
            t = next + 1e-3;
            level = min(level + 1, int(_FLW_FAR_MAX_LOD));
        } else if (level == _flw_farLod) {
            hit = true;
            break;
        } else {
            level--;
        }
    }
    if (!hit) {
        discard;
    }
    vec3 q = _flw_farTexel + dir * t;

    float margin = 0.5 * float(1 << _flw_farLod);
    float best = 1e30;
    vec4 color = vec4(0.0);
    vec4 normal = vec4(0.0);
    uvec4 material = uvec4(0u);
    float unlit = 0.0;
    for (uint i = 0u; i < 3u; i++) {
        float depth;
        float map = _flw_farMap(faces[i], q, depth);
        float error = abs(depth - map) * extent[i];
        ivec3 rect;
        ivec2 size;
        vec2 texel = _flw_farFaceTexel(_flw_farTile, faces[i], q, depth, rect, size);
        vec2 at = clamp(texel, vec2(rect.xy) + margin, vec2(rect.xy + size) - margin);
        vec3 uv = vec3(at / float(_FLW_FAR_LAYER_SIZE), rect.z);
        vec4 sampled = textureLod(_flw_farAtlasColor, uv, float(_flw_farLod));
        if (sampled.a > 0.01 && error < best) {
            best = error;
            color = sampled;
            normal = textureLod(_flw_farAtlasNormal, uv, float(_flw_farLod));
            #ifdef _FLW_FAR_SURFACE
            ivec3 materialAt = _flw_farMaterialTexel(at, rect, size);
            material = texelFetch(_flw_farAtlasMaterial, materialAt, _flw_farLod);
            normal.rgb = texelFetch(_flw_farAtlasNormal, materialAt, _flw_farLod).rgb;
            #endif
            unlit = textureLod(_flw_farAtlasAux, uv, float(_flw_farLod)).g;
        }
    }
    _flw_farWriteOpaque(q, color, normal, unlit, material);
}
