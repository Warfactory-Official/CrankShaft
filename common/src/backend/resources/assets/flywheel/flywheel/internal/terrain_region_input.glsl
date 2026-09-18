#ifndef _FLW_TERRAIN_REGION_INPUT_GLSL
#define _FLW_TERRAIN_REGION_INPUT_GLSL

// X/Z low halves share .x; .y holds Y16 and the two horizontal high bytes. .z/.w remain regionId/run.
ivec3 _flw_unpackRegionOrigin(uvec4 entry) {
    uint x = (entry.x & 0xFFFFu) | (entry.y & 0xFF0000u);
    uint z = (entry.x >> 16u) | ((entry.y >> 8u) & 0xFF0000u);
    return ivec3(int(x << 8u) >> 8, int(entry.y << 16u) >> 16, int(z << 8u) >> 8);
}

#endif
