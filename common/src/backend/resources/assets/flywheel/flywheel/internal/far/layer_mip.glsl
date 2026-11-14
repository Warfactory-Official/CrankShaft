#include "flywheel:internal/far/layer_storage.glsl"
layout(local_size_x=256) in;
layout(std430,binding=0) buffer FarLayerHeads { uint heads[]; };
layout(std430,binding=3) buffer FarLayerNodes { FarLayerNode nodes[]; };
layout(std430,binding=7) buffer FarLayerStatus { uvec4 status[6]; };
uniform uint level;
uniform uint capacity;
uniform uint inputOffset;
uniform uint outputOffset;
uniform uint width;
uniform uint pixelCount;

void main() {
    uint pixel=gl_GlobalInvocationID.x;
    if(pixel>=pixelCount||status[0].y!=0u||status[level].y!=0u) return;
    uvec2 xy=uvec2(pixel%width,pixel/width);
    uint children[4];
    for(uint child=0u;child<4u;child++) {
        uvec2 pos=xy*2u+uvec2(child&1u,child>>1u);
        children[child]=heads[inputOffset+pos.y*(width*2u)+pos.x];
    }
    uint first=_FLW_FAR_LAYER_NULL,tail=_FLW_FAR_LAYER_NULL,count=0u;
    for(;;) {
        uint source=_FLW_FAR_LAYER_NULL;
        for(uint c=0u;c<4u;c++)
            if(children[c]!=_FLW_FAR_LAYER_NULL) source=min(source,nodes[children[c]].link.y);
        if(source==_FLW_FAR_LAYER_NULL) break;
        if(status[0].x>=capacity) {atomicOr(status[level+1u].y,1u);return;}
        uint destination=atomicAdd(status[0].x,1u);
        if(destination>=capacity) {atomicOr(status[level+1u].y,1u);return;}
        FarLayerNode merged;
        merged.link=uvec4(_FLW_FAR_LAYER_NULL,source,0u,0u);
        merged.color=vec4(0);merged.normal=vec4(0);merged.light=vec4(0);
        merged.depth=vec4(1,0,0,0);merged.plane=vec4(0);merged.material=uvec4(0);
        vec2 uv=vec2(0);
        for(uint c=0u;c<4u;c++) {
            while(children[c]!=_FLW_FAR_LAYER_NULL&&nodes[children[c]].link.y==source) {
                FarLayerNode value=nodes[children[c]];
                float coverage=value.light.w*0.25;
                merged.color+=value.color*0.25;
                merged.normal+=vec4(value.normal.xyz*2.0-1.0,value.normal.w)*coverage;
                merged.light+=vec4(value.light.xyz,1.0)*coverage;
                merged.plane.xyz+=unpackSnorm4x8(value.material.w).xyz*coverage;
                merged.depth.xy=vec2(min(merged.depth.x,value.depth.x),max(merged.depth.y,value.depth.y));
                merged.material=value.material;
                merged.link.z=value.link.z;
                uv+=uintBitsToFloat(value.material.xy)*coverage;
                children[c]=value.link.x;
            }
        }
        float coverage=merged.light.w;
        merged.normal.xyz=normalize(merged.normal.xyz)*0.5+0.5;
        merged.normal.w/=coverage;merged.light.xyz/=coverage;
        merged.material.xy=floatBitsToUint(uv/coverage);
        merged.material.w=(packSnorm4x8(vec4(normalize(merged.plane.xyz),0))&0xffffffu)
                |(merged.material.w&0xff000000u);
        nodes[destination]=merged;
        if(tail==_FLW_FAR_LAYER_NULL) first=destination;
        else nodes[tail].link.x=destination;
        tail=destination;++count;
    }
    heads[outputOffset+pixel]=first;
    atomicMax(status[level+1u].x,count);
}
