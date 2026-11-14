// A far row is its part slot followed by the instance, in the instance buffer.
#define _FLW_FAR_RECORD_UINTS 1u

#define _FLW_FAR_LEVEL_CULLED 0u
#define _FLW_FAR_LEVEL_MESH 1u
#define _FLW_FAR_LEVEL_IMPOSTOR 2u

#define _FLW_FAR_PART_LIVE 1u
#define _FLW_FAR_PART_BAKED 2u
#define _FLW_FAR_ENTRY_MESH_ONLY 2u

// Texels per side of a bake scratch target and of an atlas layer.
#define _FLW_FAR_SCRATCH_SIZE 1024u
#define _FLW_FAR_LAYER_SIZE 1024u
#define _FLW_FAR_MAX_LOD 4u
// Bake depth: 1 at a face, never 0 (cleared, uncovered) inside the box.
#define _FLW_FAR_DEPTH_SPAN 0.999

layout(std140, binding = 9) uniform _FlwFar {
    uint _flw_farTick;
    float _flw_farPartialTick;
    float _flw_farMeshScale;
    // Bounds pass: new rows; entry pass: slots; mip pass: the level written.
    uint _flw_farCount;
    // xyz: camera, render-origin relative; w: pixels per block at distance 1.
    vec4 _flw_farCamera;
    // Current render-origin relative world to this frame's canonical far reversed-Z clip space.
    mat4 _flw_farOcclusionClip;
    // Full-resolution source size, pyramid's last level, enabled.
    vec4 _flw_farOcclusionSize;
    // Part selection shares the entry pass's block without rewriting it between GPU consumers.
    uint _flw_farPartCount;
};

struct FarEntry {
    // xyz: capture render origin; w: live bit 0, mesh-only bit 1.
    ivec4 origin;
    // xyz: sortable float bits, capture-origin relative; w: float bits of the mesh switch, px per block.
    uvec4 boundsMin;
    // xyz: sortable float bits; w: 1 once every part is baked.
    uvec4 boundsMax;
};

// The rows of an entry sharing one pose: kept as captured, or turning about an axis.
struct FarPart {
    // xyz: capture-origin relative; w: radians per tick, 0 for a static part.
    vec4 pivot;
    // xyz: unit axis; w: radians from the rest pose its bake is drawn in to the captured pose.
    vec4 axis;
    // xyz: capture render origin; w: start tick.
    ivec4 origin;
    // x: entry slot; y: tile slot; z: light, block | sky << 16 (0-240 each).
    // w: live/baked bits 0/1; owned upper box faces X/Y/Z in bits 2/3/4.
    uvec4 info;
    // xyz: bake box minimum, rest pose, capture-origin relative; w: layered descriptor index bits, ~0u if absent.
    vec4 boxMin;
    // The authored scope/group frame, applied after the instance and material vertex stages.
    mat4 framePose;
    // Column-major normal matrix; the first padding word holds a conservative positional radius scale.
    vec4 frameNormal0;
    vec4 frameNormal1;
    vec4 frameNormal2;
};

void _flw_farFrameSphere(FarPart part, inout vec3 center, inout float radius) {
    center = (part.framePose * vec4(center, 1.0)).xyz;
    radius *= part.frameNormal0.w;
}

// A baked appearance: per face, albedo (premultiplied; alpha = coverage), normal (encoded RGB, native diffuse in
// alpha) and aux (r: depth from the face over the box extent, 1 where uncovered; g: unlit fraction).
// Face f = axis * 2 + (negative side); the face of axis a spans
// u = axis (a + 1) % 3 by v = axis (a + 2) % 3, a negative face mirrored in u, both side by side in a pair rect.
struct FarTile {
    // xyz: per axis, pair rect origin x | y << 10 | layer << 20; w: float bits of texels per block.
    uvec4 pairs;
    // xyz: box extent in texels, multiples of 1 << _FLW_FAR_MAX_LOD; w: certified opaque plane axis + 1, or 0.
    uvec4 extent;
    // Per face 0-3, then 4-5: float bits of the nearest content's depth from the face.
    uvec4 inset0;
    // xy: face 4/5 insets; z: certified opaque plane's float offset in the tile's texel frame.
    uvec4 inset1;
};

struct FarBakeJob {
    // xyz: per axis, scratch pair rect origin x | y << 16; w: part slot.
    uvec4 scratch;
    // x: tile slot.
    uvec4 tile;
};

uint _flw_farSortable(float f) {
    uint u = floatBitsToUint(f);
    return (u & 0x80000000u) != 0u ? ~u : u | 0x80000000u;
}

float _flw_farUnsortable(uint u) {
    return uintBitsToFloat((u & 0x80000000u) != 0u ? u & 0x7FFFFFFFu : ~u);
}

// Unit axis.
vec3 _flw_farRotate(vec3 v, vec3 axis, float angle) {
    float c = cos(angle);
    return v * c + cross(axis, v) * sin(angle) + axis * dot(axis, v) * (1.0 - c);
}

float _flw_farAngle(float radiansPerTick, int startTick) {
    return radiansPerTick * (float(int(_flw_farTick) - startTick) + _flw_farPartialTick);
}

// The pair rect of axis a: texel origin and layer.
ivec3 _flw_farPairRect(FarTile tile, uint a) {
    uint bits = tile.pairs[a];
    return ivec3(bits & 0x3FFu, (bits >> 10u) & 0x3FFu, bits >> 20u);
}

// The depth from the face of a covered (z > 0) scratch texel.
float _flw_farBakeDepth(float z) {
    return clamp((1.0 - z) / _FLW_FAR_DEPTH_SPAN, 0.0, 1.0);
}

float _flw_farInset(FarTile tile, uint face) {
    return uintBitsToFloat(face < 4u ? tile.inset0[face] : tile.inset1[face - 4u]);
}

// The level-0 texel of box texel point q on face f, and its depth from the face over the extent.
vec2 _flw_farFaceTexel(FarTile tile, uint face, vec3 q, out float depth, out ivec3 rect, out ivec2 size) {
    uint a = face >> 1u;
    uint ua = (a + 1u) % 3u;
    uint va = (a + 2u) % 3u;
    bool negative = (face & 1u) != 0u;
    vec3 extent = vec3(tile.extent.xyz);
    depth = (negative ? q[a] : extent[a] - q[a]) / extent[a];
    rect = _flw_farPairRect(tile, a);
    size = ivec2(tile.extent[ua], tile.extent[va]);
    if (negative) {
        rect.x += size.x;
    }
    return vec2(rect.xy) + vec2(negative ? extent[ua] - q[ua] : q[ua], q[va]);
}

#ifndef _FLW_FAR_NO_ROWS
uint _flw_farRowPart(uint objectUint) {
    return _flw_instances[objectUint - _FLW_FAR_RECORD_UINTS];
}
#endif
