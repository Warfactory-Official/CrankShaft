#include "flywheel:internal/far/layer_storage.glsl"
layout(local_size_x=256) in;
layout(std430,binding=0) buffer FarLayerHeads { uint heads[]; };
layout(std430,binding=3) buffer FarLayerNodes { FarLayerNode nodes[]; };
layout(std430,binding=7) buffer FarLayerStatus { uvec4 status[6]; };
uniform uint pixelCount;
uniform bool sourceOrder;
uniform bool countLayers;
uniform bool certifyQuads;
#include "flywheel:internal/far/layer_quad.glsl"

bool before(uint a,uint b) {
    if(sourceOrder) return nodes[a].link.y<nodes[b].link.y
            ||nodes[a].link.y==nodes[b].link.y&&nodes[a].depth.x<=nodes[b].depth.x;
    return nodes[a].depth.x<nodes[b].depth.x
            ||nodes[a].depth.x==nodes[b].depth.x&&nodes[a].link.y<=nodes[b].link.y;
}
void main() {
    uint pixel=gl_GlobalInvocationID.x;
    if(certifyQuads) {_flw_farCertifyQuad(pixel);return;}
    if(pixel>=pixelCount||status[0].y!=0u) return;
    uint list=heads[pixel];
    if(list==_FLW_FAR_LAYER_NULL) return;
    uint count=0u,node=list;
    while(node!=_FLW_FAR_LAYER_NULL) {++count;node=nodes[node].link.x;}
    if(countLayers) atomicMax(status[1].x,count);
    // Bottom-up linked merge sort uses no local K-sized payload and retains every raw record.
    uint span=1u;
    for(;;) {
        uint p=list;
        list=_FLW_FAR_LAYER_NULL;
        uint tail=_FLW_FAR_LAYER_NULL,merges=0u;
        while(p!=_FLW_FAR_LAYER_NULL) {
            ++merges;
            uint q=p,remaining=0u;
            for(uint i=0u;i<span&&q!=_FLW_FAR_LAYER_NULL;i++) {
                ++remaining;q=nodes[q].link.x;
            }
            uint right=span;
            while(remaining>0u||(right>0u&&q!=_FLW_FAR_LAYER_NULL)) {
                uint chosen;
                if(remaining==0u) {chosen=q;q=nodes[q].link.x;--right;}
                else if(right==0u||q==_FLW_FAR_LAYER_NULL||before(p,q)) {
                    chosen=p;p=nodes[p].link.x;--remaining;
                } else {chosen=q;q=nodes[q].link.x;--right;}
                if(tail==_FLW_FAR_LAYER_NULL) list=chosen;
                else nodes[tail].link.x=chosen;
                tail=chosen;
            }
            p=q;
        }
        nodes[tail].link.x=_FLW_FAR_LAYER_NULL;
        if(merges<=1u) break;
        span*=2u;
    }
    heads[pixel]=list;
}
