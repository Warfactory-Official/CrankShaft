#include "flywheel:internal/far/far.glsl"

layout(local_size_x = 256) in;

layout(std430, binding = 10) restrict readonly buffer BakeJobBuffer {
    FarBakeJob _flw_farBakeJobs[];
};

layout(std430, binding = 12) restrict readonly buffer TileBuffer {
    FarTile _flw_farTiles[];
};

// Levels _flw_farCount - 1 (read) and _flw_farCount (written).
layout(binding = 0, rgba8) uniform restrict readonly image2DArray _flw_farColorIn;
layout(binding = 1, rgba8) uniform restrict writeonly image2DArray _flw_farColorOut;
layout(binding = 2, rg16) uniform restrict readonly image2DArray _flw_farAuxIn;
layout(binding = 3, rg16) uniform restrict writeonly image2DArray _flw_farAuxOut;
layout(binding = 4, rgba8) uniform restrict readonly image2DArray _flw_farNormalIn;
layout(binding = 5, rgba8) uniform restrict writeonly image2DArray _flw_farNormalOut;
layout(binding = 6, rgba32ui) uniform restrict readonly uimage2DArray _flw_farMaterialIn;
layout(binding = 7, rgba32ui) uniform restrict writeonly uimage2DArray _flw_farMaterialOut;

// Per job face, one group each: one level of its tile from the level above. Colour averages (premultiplied); depth
// keeps the nearest covered child (silhouettes dilate, as LoD hosts do), with that child's material and geometric
// normal. Native diffuse and the unlit fraction weigh by coverage.
void main() {
    uint face = gl_WorkGroupID.x % 6u;
    FarTile tile = _flw_farTiles[_flw_farBakeJobs[gl_WorkGroupID.x / 6u].tile.x];
    uint a = face >> 1u;
    ivec2 size = ivec2(tile.extent[(a + 1u) % 3u], tile.extent[(a + 2u) % 3u]);
    ivec3 rect = _flw_farPairRect(tile, a);
    if ((face & 1u) != 0u) {
        rect.x += size.x;
    }
    int level = int(_flw_farCount);
    size >>= level;
    ivec2 origin = rect.xy >> level;
    for (int i = int(gl_LocalInvocationIndex); i < size.x * size.y; i += 256) {
        ivec2 texel = ivec2(i % size.x, i / size.x);
        vec4 color = vec4(0.0);
        float depth = 1.0;
        float unlit = 0.0;
        vec4 normal = vec4(0.0);
        uvec4 material = uvec4(0u);
        for (int c = 0; c < 4; c++) {
            ivec3 child = ivec3((origin + texel) * 2 + ivec2(c & 1, c >> 1), rect.z);
            vec4 childColor = imageLoad(_flw_farColorIn, child);
            vec4 childAux = imageLoad(_flw_farAuxIn, child);
            color += childColor;
            vec4 childNormal = imageLoad(_flw_farNormalIn, child);
            if (childColor.a > 0.0 && (childAux.r < depth || material.z == 0u)) {
                depth = childAux.r;
                normal.rgb = childNormal.rgb;
                material = imageLoad(_flw_farMaterialIn, child);
            }
            unlit += childAux.g * childColor.a;
            normal.a += childNormal.a * childColor.a;
        }
        ivec3 at = ivec3(origin + texel, rect.z);
        imageStore(_flw_farColorOut, at, color * 0.25);
        imageStore(_flw_farAuxOut, at, vec4(depth, color.a > 0.0 ? unlit / color.a : 0.0, 0.0, 0.0));
        imageStore(_flw_farNormalOut, at, color.a > 0.0 ? vec4(normal.rgb, normal.a / color.a) : vec4(0.0));
        imageStore(_flw_farMaterialOut, at, material);
    }
}
