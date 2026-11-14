#include "flywheel:internal/far/layer_sample.glsl"
layout(local_size_x=256) in;
layout(std430,binding=9) readonly buffer RawLayerSurfaces { FarLayerSurface rawSurfaces[]; };
layout(std430,binding=13) readonly buffer RawLayerHeads { uint heads[]; };
layout(std430,binding=14) readonly buffer RawLayerNodes { FarLayerNode nodes[]; };
layout(std430,binding=11) buffer LayerCompareResults { uint results[]; };
uniform uint candidate;
uniform uint resultIndex;
uniform uvec3 extent;
uniform uvec3 scratch;

vec4 halfPrecision(vec4 value) {
    return vec4(unpackHalf2x16(packHalf2x16(value.xy)),unpackHalf2x16(packHalf2x16(value.zw)));
}
void main() {
    uint level=gl_WorkGroupID.x/6u,face=gl_WorkGroupID.x%6u;
    FarLayeredTile tile=_flw_layerTiles[candidate];
    if((tile.records.z&(1u<<level))==0u) return;
    uint offset=0u;
    for(uint i=0u;i<level;i++) {uint w=1024u>>i;offset+=w*w;}
    uint width=1024u>>level,axis=face>>1u;
    uvec2 interior=uvec2(extent[(axis+1u)%3u],extent[(axis+2u)%3u])>>level;
    uvec2 size=interior+2u;
    uint halo=_FLW_FAR_LAYER_HALO>>level;
    uvec2 start=(uvec2(scratch[axis]&65535u,scratch[axis]>>16u)>>level)+halo-1u;
    if((face&1u)!=0u) start.x+=interior.x+2u*halo;
    for(uint pixel=gl_LocalInvocationIndex;pixel<size.x*size.y;pixel+=256u) {
        uvec2 xy=uvec2(pixel%size.x,pixel/size.x);
        uint node=heads[offset+(start.y+xy.y)*width+start.x+xy.x];
        for(uint rank=0u;rank<_flw_farLayerRanks(tile,level);rank++) {
            ivec3 at=_flw_farLayerTexel(tile,rank,face,ivec2(xy)-1,level,extent);
            uint id=texelFetch(_flw_layerSurface,at,0).r;
            if(node==_FLW_FAR_LAYER_NULL) {
                if(id!=0u) atomicOr(results[resultIndex],1u);
                continue;
            }
            FarLayerNode value=nodes[node];
            bool different=id!=value.link.y;
            different=different||any(notEqual(texelFetch(_flw_layerAlbedo,at,0),halfPrecision(value.color)));
            different=different||any(notEqual(texelFetch(_flw_layerNormals,at,0),halfPrecision(value.normal)));
            different=different||any(notEqual(texelFetch(_flw_layerLight,at,0),halfPrecision(value.light)));
            different=different||any(notEqual(texelFetch(_flw_layerDepth,at,0).xy,value.depth.xy));
            different=different||any(notEqual(texelFetch(_flw_layerMaterials,at,0),value.material));
            FarLayerSurface expected=rawSurfaces[value.link.y-1u];
            if(id!=0u) {
                FarLayerSurface stored=_flw_layerSources[tile.source.x+id-1u];
                different=different||any(notEqual(floatBitsToUint(stored.plane),floatBitsToUint(expected.plane)))
                        ||any(notEqual(stored.info,expected.info));
            }
            if(different) atomicOr(results[resultIndex],1u);
            node=value.link.x;
        }
        if(node!=_FLW_FAR_LAYER_NULL) atomicOr(results[resultIndex],1u);
    }
}
