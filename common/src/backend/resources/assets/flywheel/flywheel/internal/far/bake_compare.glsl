#include "flywheel:internal/far/far.glsl"
#include "flywheel:internal/far/opaque_bounds.glsl"

layout(std430, binding = 15) restrict readonly buffer PartBuffer {
    FarPart _flw_farParts[];
};

layout(local_size_x = 256) in;

layout(std430, binding = 10) restrict readonly buffer BakeJobBuffer {
    FarBakeJob _flw_farBakeJobs[];
};

layout(std430, binding = 12) restrict readonly buffer TileBuffer {
    FarTile _flw_farTiles[];
};

// Per compare: mismatched texels.
layout(std430, binding = 13) restrict buffer CompareResultBuffer {
    uint _flw_farCompareResults[];
};

// Per compare: x probe job, y candidate tile of the same extent and density.
layout(std430, binding = 14) restrict readonly buffer CompareBuffer {
    uvec4 _flw_farCompares[];
};

layout(binding = 12) uniform sampler2D _flw_farScratchDepth;
layout(binding = 0, rgba8) uniform restrict readonly image2D _flw_farScratchColor;
layout(binding = 1, rgba8) uniform restrict readonly image2DArray _flw_farAtlasColor;
layout(binding = 2, rg16) uniform restrict readonly image2DArray _flw_farAtlasAux;
layout(binding = 3, rgba8) uniform restrict readonly image2D _flw_farScratchNormal;
layout(binding = 4, rgba8) uniform restrict readonly image2DArray _flw_farAtlasNormal;
layout(binding = 5, rgba32ui) uniform restrict readonly uimage2D _flw_farScratchMaterial;
layout(binding = 6, rgba32ui) uniform restrict readonly uimage2DArray _flw_farAtlasMaterial;

shared uint _flw_farMismatches;

// Per compare face, one group each: texels of a probe's scratch view whose coverage, colour, normal or depth (over 1.5 texels)
// differ from the candidate tile's level 0.
void main() {
    uint compare = gl_WorkGroupID.x / 6u;
    uint face = gl_WorkGroupID.x % 6u;
    uvec4 pair = _flw_farCompares[compare];
    FarBakeJob bake = _flw_farBakeJobs[pair.x];
    FarTile tile = _flw_farTiles[pair.y];
    uvec2 plane = _flw_farOpaquePlane(pair.x, _flw_farParts[bake.scratch.w].boxMin.xyz,
            uintBitsToFloat(tile.pairs.w));
    if (any(notEqual(plane, uvec2(tile.extent.w, tile.inset1.z)))) {
        if (face == 0u && gl_LocalInvocationIndex == 0u)
            atomicMax(_flw_farCompareResults[compare], 0x7fffffffu);
        return;
    }
    uint a = face >> 1u;
    ivec2 size = ivec2(tile.extent[(a + 1u) % 3u], tile.extent[(a + 2u) % 3u]);
    ivec3 rect = _flw_farPairRect(tile, a);
    ivec2 scratch = ivec2(bake.scratch[a] & 0xFFFFu, bake.scratch[a] >> 16u);
    if ((face & 1u) != 0u) {
        rect.x += size.x;
        scratch.x += size.x;
    }
    if (gl_LocalInvocationIndex == 0u) {
        _flw_farMismatches = 0u;
    }
    barrier();

    float depthTexels = float(tile.extent[a]);
    uint mismatches = 0u;
    for (int i = int(gl_LocalInvocationIndex); i < size.x * size.y; i += 256) {
        ivec2 texel = ivec2(i % size.x, i / size.x);
        float z = texelFetch(_flw_farScratchDepth, scratch + texel, 0).r;
        ivec3 at = ivec3(rect.xy + texel, rect.z);
        vec4 candidate = imageLoad(_flw_farAtlasColor, at);
        if ((z > 0.0) != (candidate.a > 0.0)) {
            mismatches++;
        } else if (z > 0.0) {
            vec3 difference = abs(imageLoad(_flw_farScratchColor, scratch + texel).rgb - candidate.rgb);
            vec4 normalDifference = abs(imageLoad(_flw_farScratchNormal, scratch + texel)
                    - imageLoad(_flw_farAtlasNormal, at));
            uvec4 material = imageLoad(_flw_farScratchMaterial, scratch + texel);
            uvec4 candidateMaterial = imageLoad(_flw_farAtlasMaterial, at);
            vec2 uvDifference = abs(uintBitsToFloat(material.xy) - uintBitsToFloat(candidateMaterial.xy));
            vec4 tangent = unpackSnorm4x8(material.w);
            vec4 candidateTangent = unpackSnorm4x8(candidateMaterial.w);
            float depth = _flw_farBakeDepth(z);
            if (max(difference.r, max(difference.g, difference.b)) > 0.125
                    || any(greaterThan(normalDifference, vec4(0.125)))
                    || material.z != candidateMaterial.z || any(greaterThan(uvDifference, vec2(1.0 / 65536.0)))
                    || dot(tangent.xyz, candidateTangent.xyz) < 0.98
                    || (material.w & 0xFF000000u) != (candidateMaterial.w & 0xFF000000u)
                    || abs(depth - imageLoad(_flw_farAtlasAux, at).r) * depthTexels > 1.5) {
                mismatches++;
            }
        }
    }
    atomicAdd(_flw_farMismatches, mismatches);
    barrier();
    if (gl_LocalInvocationIndex == 0u) {
        atomicAdd(_flw_farCompareResults[compare], _flw_farMismatches);
    }
}
