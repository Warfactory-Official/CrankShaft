#include "flywheel:internal/far/far.glsl"
#include "flywheel:internal/far/layer_storage.glsl"
layout(local_size_x=256) in;
layout(std430,binding=0) readonly buffer FarLayerHeads { uint heads[]; };
layout(std430,binding=3) readonly buffer FarLayerNodes { FarLayerNode nodes[]; };
layout(std430,binding=11) readonly buffer LayerRects { uvec4 rects[]; };
layout(binding=0,rgba16f) writeonly uniform image2DArray layerColor;
layout(binding=1,rgba16f) writeonly uniform image2DArray layerNormal;
layout(binding=2,rgba16f) writeonly uniform image2DArray layerLight;
layout(binding=3,rg32f) writeonly uniform image2DArray layerDepth;
layout(binding=4,rgba32ui) writeonly uniform uimage2DArray layerMaterial;
layout(binding=5,r32ui) writeonly uniform uimage2DArray layerSurface;
uniform uint level;
uniform uint inputOffset;
uniform uint width;
uniform uint ranks;
uniform uvec3 extent;
uniform uvec3 scratch;
uniform uint rectBase;
void main() {
    uint face = gl_WorkGroupID.x;
    uint axis = face >> 1u;
    uvec2 interior = uvec2(extent[(axis+1u)%3u],extent[(axis+2u)%3u]) >> level;
    uvec2 size = interior + 2u;
    uint halo = _FLW_FAR_LAYER_HALO >> level;
    uvec2 source = (uvec2(scratch[axis]&65535u,scratch[axis]>>16u) >> level) + halo - 1u;
    if ((face&1u)!=0u) source.x += interior.x + 2u * halo;
    for (uint pixel=gl_LocalInvocationIndex;pixel<size.x*size.y;pixel+=256u) {
        uvec2 xy=uvec2(pixel%size.x,pixel/size.x);
        uint node=heads[inputOffset+(source.y+xy.y)*width+source.x+xy.x];
        for(uint rank=0u;rank<ranks;++rank) {
            uint address=rects[rectBase+rank][axis];
            ivec3 dest=ivec3(ivec2(address&1023u,(address>>10u)&1023u),int(address>>20u));
            if ((face&1u)!=0u) dest.x+=int(size.x);
            dest.xy+=ivec2(xy);
            if(node==_FLW_FAR_LAYER_NULL) {
                imageStore(layerColor,dest,vec4(0));
                imageStore(layerNormal,dest,vec4(0));
                imageStore(layerLight,dest,vec4(0));
                imageStore(layerDepth,dest,vec4(1,1,0,0));
                imageStore(layerMaterial,dest,uvec4(0));
                imageStore(layerSurface,dest,uvec4(0));
            } else {
                FarLayerNode value=nodes[node];
                imageStore(layerColor,dest,value.color);
                imageStore(layerNormal,dest,value.normal);
                imageStore(layerLight,dest,value.light);
                imageStore(layerDepth,dest,value.depth);
                imageStore(layerMaterial,dest,value.material);
                imageStore(layerSurface,dest,uvec4(value.link.y,0,0,0));
                node=value.link.x;
            }
        }
    }
}
