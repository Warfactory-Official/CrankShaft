#include "flywheel:internal/render_origin.glsl"
#include "flywheel:internal/far/far.glsl"

layout(std430, binding = 15) restrict readonly buffer PartBuffer {
    FarPart _flw_farParts[];
};

layout(std430, binding = 11) restrict readonly buffer ImpostorBuffer {
    uint _flw_farImpostors[];
};

layout(std430, binding = 12) restrict readonly buffer TileBuffer {
    FarTile _flw_farTiles[];
};

flat out uint _flw_farTileSlot;
// The eye in the tile's texel frame.
flat out vec3 _flw_farEye;
flat out mat4 _flw_farToClip;
// xy: lightmap coordinate; z: level of detail.
flat out vec3 _flw_farShade;
flat out mat3 _flw_farRotation;
out vec3 _flw_farTexel;

// Two counter-clockwise triangles of a positive face in (u, v); a negative face swaps u and v.
const vec2 _FLW_FAR_CORNERS[6] = vec2[6](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0), vec2(0.0, 0.0),
        vec2(1.0, 1.0), vec2(0.0, 1.0));

// One baked part's box per instance, 6 faces x 2 triangles, tightened to its content and posed like its rows.
void main() {
    uint slot = _flw_farImpostors[gl_InstanceID];
    FarPart part = _flw_farParts[slot];
    FarTile tile = _flw_farTiles[part.info.y];
    float density = uintBitsToFloat(tile.pairs.w);
    vec3 extent = vec3(tile.extent.xyz);
    vec3 lo, hi;
    if (tile.extent.w != 0u) {
        lo = vec3(0.0);
        hi = extent;
        uint axis = tile.extent.w - 1u;
        lo[axis] = hi[axis] = uintBitsToFloat(tile.inset1.z);
    } else {
        lo = vec3(_flw_farInset(tile, 1u), _flw_farInset(tile, 3u), _flw_farInset(tile, 5u)) * extent;
        hi = (1.0 - vec3(_flw_farInset(tile, 0u), _flw_farInset(tile, 2u), _flw_farInset(tile, 4u))) * extent;
    }
    if (any(greaterThan(lo, hi))) {
        gl_Position = vec4(0.0);
        return;
    }

    uint face = uint(gl_VertexID) / 6u;
    uint a = face >> 1u;
    bool negative = (face & 1u) != 0u;
    vec2 corner = _FLW_FAR_CORNERS[uint(gl_VertexID) % 6u];
    if (negative) {
        corner = corner.yx;
    }
    vec3 q;
    q[a] = negative ? lo[a] : hi[a];
    q[(a + 1u) % 3u] = mix(lo[(a + 1u) % 3u], hi[(a + 1u) % 3u], corner.x);
    q[(a + 2u) % 3u] = mix(lo[(a + 2u) % 3u], hi[(a + 2u) % 3u], corner.y);

    mat3 rotation = mat3(1.0);
    vec3 pivot = vec3(0.0);
    if (part.pivot.w != 0.0) {
        float angle = part.axis.w + _flw_farAngle(part.pivot.w, part.origin.w);
        rotation = mat3(_flw_farRotate(vec3(1.0, 0.0, 0.0), part.axis.xyz, angle),
                _flw_farRotate(vec3(0.0, 1.0, 0.0), part.axis.xyz, angle),
                _flw_farRotate(vec3(0.0, 0.0, 1.0), part.axis.xyz, angle));
        pivot = part.pivot.xyz;
    }
    vec3 offset = vec3(part.origin.xyz - _flw_renderOrigin.xyz) + pivot;
    // Texel frame to render-origin relative: offset + rotation * (boxMin - pivot + q / density).
    mat4 toWorld = mat4(vec4(rotation[0] / density, 0.0), vec4(rotation[1] / density, 0.0),
            vec4(rotation[2] / density, 0.0), vec4(offset + rotation * (part.boxMin.xyz - pivot), 1.0));
    _flw_farToClip = ProjMat * ModelViewMat * toWorld;
    _flw_farEye = (transpose(rotation) * (_flw_farCamera.xyz - offset) + pivot - part.boxMin.xyz) * density;

    // At the box's nearest point.
    float pixelsPerBlock = _flw_farCamera.w / max(distance(clamp(_flw_farEye, lo, hi), _flw_farEye) / density, 1.0);
    float lod = clamp(round(log2(max(density / pixelsPerBlock, 1.0))), 0.0, float(_FLW_FAR_MAX_LOD));
    uvec2 light = uvec2(part.info.z & 0xFFFFu, part.info.z >> 16u);
    _flw_farShade = vec3(vec2(light) / 256.0, lod);
    _flw_farTileSlot = part.info.y;
    _flw_farRotation = rotation;
    _flw_farTexel = q;
    gl_Position = _flw_farToClip * vec4(q, 1.0);
}
