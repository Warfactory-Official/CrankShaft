#include "flywheel:internal/far/far.glsl"
#include "flywheel:internal/far/opaque_bounds.glsl"

layout(std430, binding = 15) restrict readonly buffer PartBuffer {
    FarPart _flw_farParts[];
};

layout(local_size_x = 256) in;

layout(std430, binding = 10) restrict readonly buffer BakeJobBuffer {
    FarBakeJob _flw_farBakeJobs[];
};

layout(std430, binding = 12) restrict buffer TileBuffer {
    FarTile _flw_farTiles[];
};

layout(binding = 12) uniform sampler2D _flw_farScratchDepth;
layout(binding = 0, rgba8) uniform restrict readonly image2D _flw_farScratchColor;
layout(binding = 1, rgba8) uniform restrict writeonly image2DArray _flw_farAtlasColor;
layout(binding = 2, rg16) uniform restrict writeonly image2DArray _flw_farAtlasAux;
layout(binding = 3, rgba8) uniform restrict readonly image2D _flw_farScratchNormal;
layout(binding = 4, rgba8) uniform restrict writeonly image2DArray _flw_farAtlasNormal;
layout(binding = 5, rgba32ui) uniform restrict readonly uimage2D _flw_farScratchMaterial;
layout(binding = 6, rgba32ui) uniform restrict writeonly uimage2DArray _flw_farAtlasMaterial;

shared uint _flw_farNearest;

// Per job face, one group each: the scratch view into its tile's level 0 (covered where depth was written) and the
// depth of the content nearest the face.
void main() {
    uint job = gl_WorkGroupID.x / 6u;
    uint face = gl_WorkGroupID.x % 6u;
    FarBakeJob bake = _flw_farBakeJobs[job];
    FarTile tile = _flw_farTiles[bake.tile.x];
    uint a = face >> 1u;
    ivec2 size = ivec2(tile.extent[(a + 1u) % 3u], tile.extent[(a + 2u) % 3u]);
    ivec3 atlas = _flw_farPairRect(tile, a);
    ivec2 scratch = ivec2(bake.scratch[a] & 0xFFFFu, bake.scratch[a] >> 16u);
    if ((face & 1u) != 0u) {
        atlas.x += size.x;
        scratch.x += size.x;
    }
    if (gl_LocalInvocationIndex == 0u) {
        _flw_farNearest = floatBitsToUint(1.0);
        if (face == 0u) {
            uvec2 plane = _flw_farOpaquePlane(job, _flw_farParts[bake.scratch.w].boxMin.xyz,
                    uintBitsToFloat(tile.pairs.w));
            _flw_farTiles[bake.tile.x].extent.w = plane.x;
            _flw_farTiles[bake.tile.x].inset1.z = plane.y;
        }
    }
    barrier();

    uint nearest = floatBitsToUint(1.0);
    for (int i = int(gl_LocalInvocationIndex); i < size.x * size.y; i += 256) {
        ivec2 texel = ivec2(i % size.x, i / size.x);
        float z = texelFetch(_flw_farScratchDepth, scratch + texel, 0).r;
        vec4 color = imageLoad(_flw_farScratchColor, scratch + texel);
        bool covered = z > 0.0;
        float depth = covered ? _flw_farBakeDepth(z) : 1.0;
        ivec3 at = ivec3(atlas.xy + texel, atlas.z);
        imageStore(_flw_farAtlasColor, at, covered ? vec4(color.rgb, 1.0) : vec4(0.0));
        imageStore(_flw_farAtlasAux, at, vec4(depth, covered ? color.a : 0.0, 0.0, 0.0));
        imageStore(_flw_farAtlasNormal, at, covered ? imageLoad(_flw_farScratchNormal, scratch + texel) : vec4(0.0));
        imageStore(_flw_farAtlasMaterial, at, covered ? imageLoad(_flw_farScratchMaterial, scratch + texel) : uvec4(0u));
        if (covered) {
            nearest = min(nearest, floatBitsToUint(depth));
        }
    }
    atomicMin(_flw_farNearest, nearest);
    barrier();
    if (gl_LocalInvocationIndex == 0u) {
        if (face < 4u) {
            _flw_farTiles[bake.tile.x].inset0[face] = _flw_farNearest;
        } else {
            _flw_farTiles[bake.tile.x].inset1[face - 4u] = _flw_farNearest;
        }
    }
}
