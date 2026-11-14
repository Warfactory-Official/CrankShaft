#define _FLW_FAR_LAYER_NODE_BYTES 112u
#define _FLW_FAR_LAYER_NULL 0xffffffffu
// One complete texel of filter support at the coarsest retained mip.
#define _FLW_FAR_LAYER_HALO 16u
#define _FLW_FAR_PART_OWNED_MAX_SHIFT 2u

bool _flw_farLayerOwns(vec3 point,vec3 extent,uint ownedMax) {
    for(uint axis=0u;axis<3u;axis++) {
        if(point[axis]<0.0||point[axis]>extent[axis]) return false;
        if(point[axis]==extent[axis]&&(ownedMax&(1u<<axis))==0u) return false;
    }
    return true;
}

struct FarLayerNode {
    uvec4 link; // next, local source id, authored flags, reserved
    vec4 color; // coverage-premultiplied albedo and authored alpha
    vec4 normal; // encoded shading normal, native diffuse
    vec4 light; // intrinsic block/sky coordinates, unlit, geometric coverage
    uvec4 material;
    vec4 plane;
    vec4 depth; // conservative face depth interval in xy
};
struct FarLayerSurface {
    vec4 plane;
    uvec4 info; // authored flags, source ordinal, certified quad's one-based first triangle (0: independent), captured
};
struct FarLayeredTile {
    uvec4 records; // first mip header, maximum ranks, valid mip mask, clip max Z float bits
    uvec4 source; // first surface, surface count, clip max X/Y float bits
};
struct FarLayerHit {
    vec4 positionDepth;
    vec4 albedo;
    vec4 normalDiffuse;
    vec4 lightCoverage;
    uvec4 material;
    uvec4 identity;
    vec4 tangent;
};
