#include "flywheel:internal/far/far.glsl"
#include "flywheel:internal/far/row_box.glsl"

layout(local_size_x = 64) in;

struct FarBrickGrid {
    // xyz: the part's rest-pose bounds minimum.
    vec4 origin;
    // xyz: a brick's size; w: layered rows include contact with an owning neighbour's lower face.
    vec4 size;
    // xyz: bricks per axis; w: index of the first brick in the brick bounds.
    uvec4 count;
};

// objectUint, model index, type id, index of the row's part among this pass's grids.
layout(std430, binding = 0) restrict readonly buffer BrickRowBuffer {
    uvec4 _flw_farBrickRows[];
};

layout(std430, binding = 10) restrict readonly buffer BrickGridBuffer {
    FarBrickGrid _flw_farBrickGrids[];
};

// Per brick: sortable bounds of its rows' boxes clipped to it.
layout(std430, binding = 13) restrict buffer BrickBoundsBuffer {
    uvec4 _flw_farBrickBounds[];
};

// First count uvec2s: reached-brick mask per row (x fastest, then y, z). Layered rows append three uvec2s
// of rest-space bounds each, used to select real filter support at the eventual brick density.
layout(std430, binding = 14) restrict writeonly buffer BrickMaskBuffer {
    uvec2 _flw_farBrickMasks[];
};

// Each row's rest-pose box into the bricks of its part's grid it reaches.
void main() {
    uint index = gl_GlobalInvocationID.x;
    if (index >= _flw_farCount) {
        return;
    }
    uvec4 row = _flw_farBrickRows[index];
    vec3 lo;
    vec3 hi;
    _flw_farRowBox(row, _flw_farParts[_flw_farRowPart(row.x)], lo, hi);
    FarBrickGrid grid = _flw_farBrickGrids[row.w];
    if (grid.size.w != 0.0) {
        uint bound = _flw_farCount + index * 3u;
        _flw_farBrickMasks[bound] = floatBitsToUint(lo.xy);
        _flw_farBrickMasks[bound + 1u] = floatBitsToUint(vec2(lo.z, hi.x));
        _flw_farBrickMasks[bound + 2u] = floatBitsToUint(hi.yz);
    }
    ivec3 last = ivec3(grid.count.xyz) - 1;
    ivec3 first = clamp(ivec3(floor((lo - grid.origin.xyz) / grid.size.xyz)), ivec3(0), last);
    // Layered cells own their lower face, so a row ending there must also reach that cell.
    vec3 relativeHi = (hi - grid.origin.xyz) / grid.size.xyz;
    ivec3 end = clamp(grid.size.w != 0.0 ? ivec3(floor(relativeHi)) : ivec3(ceil(relativeHi)) - 1,
            first, last);
    uvec2 mask = uvec2(0u);
    for (int z = first.z; z <= end.z; z++) {
        for (int y = first.y; y <= end.y; y++) {
            for (int x = first.x; x <= end.x; x++) {
                uint brick = uint(x) + grid.count.x * (uint(y) + grid.count.y * uint(z));
                vec3 cell = grid.origin.xyz + vec3(x, y, z) * grid.size.xyz;
                vec3 clippedLo = max(lo, cell);
                vec3 clippedHi = min(hi, cell + grid.size.xyz);
                uint at = (grid.count.w + brick) * 2u;
                atomicMin(_flw_farBrickBounds[at].x, _flw_farSortable(clippedLo.x));
                atomicMin(_flw_farBrickBounds[at].y, _flw_farSortable(clippedLo.y));
                atomicMin(_flw_farBrickBounds[at].z, _flw_farSortable(clippedLo.z));
                atomicMax(_flw_farBrickBounds[at + 1u].x, _flw_farSortable(clippedHi.x));
                atomicMax(_flw_farBrickBounds[at + 1u].y, _flw_farSortable(clippedHi.y));
                atomicMax(_flw_farBrickBounds[at + 1u].z, _flw_farSortable(clippedHi.z));
                mask[brick >> 5u] |= 1u << (brick & 31u);
            }
        }
    }
    _flw_farBrickMasks[index] = mask;
}
