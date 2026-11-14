#include "flywheel:internal/far/far.glsl"
#include "flywheel:internal/far/layer_storage.glsl"

layout(std430,binding=0) readonly buffer FarLayerDescriptors { FarLayeredTile _flw_layerTiles[]; };
layout(std430,binding=3) readonly buffer FarLayerRectangles { uvec4 _flw_layerRects[]; };
layout(std430,binding=7) readonly buffer FarLayerSourcePlanes { FarLayerSurface _flw_layerSources[]; };
layout(std430,binding=12) readonly buffer FarLayerOpaqueTiles { FarTile _flw_layerOpaqueTiles[]; };
layout(std430,binding=15) readonly buffer FarLayerParts { FarPart _flw_layerParts[]; };
layout(binding=10) uniform sampler2DArray _flw_layerAlbedo;
layout(binding=11) uniform sampler2DArray _flw_layerNormals;
layout(binding=12) uniform sampler2DArray _flw_layerLight;
layout(binding=13) uniform sampler2DArray _flw_layerDepth;
layout(binding=14) uniform usampler2DArray _flw_layerMaterials;
layout(binding=15) uniform usampler2DArray _flw_layerSurface;

uniform mat4 _flw_farLayerViewProjection;
uniform ivec3 _flw_farLayerRenderOrigin;
uniform vec3 _flw_farLayerCamera;
uniform bool _flw_farLayerZeroToOne;
uniform float _flw_farLayerPixelScale;

struct FarLayerRay {
    uint partSlot;
    uint firstSurface;
    uint surfaceCount;
    uint lastSource;
    uint ownedMax;
    vec3 origin;
    vec3 direction;
    vec3 offset;
    vec3 pivot;
    vec3 boxMin;
    vec3 extent;
    vec3 clipExtent;
    mat3 rotation;
    float density;
    float cursor;
    float cursorError;
    float end;
    float endError;
    float lastTime;
    uint lod;
};
struct FarLayerSample {
    vec4 color;
    vec4 normal;
    vec4 light;
    uvec4 material;
};
uint _flw_farLayerAxis(vec3 normal) {
    vec3 n=abs(normal);
    return n.x>=max(n.y,n.z)?0u:n.y>=n.z?1u:2u;
}
// Positive error bounds are rounded outward. Bounds follow the actual arithmetic, in brick texels and ray time;
// a fixed spatial tolerance would depend on camera distance and could give both adjacent bricks the same hit.
float _flw_farLayerUpper(float value) {
    return uintBitsToFloat(min(floatBitsToUint(value),0x7f7fffffu)+1u);
}
float _flw_farLayerUlp(float value) {
    float magnitude=abs(value);
    return _flw_farLayerUpper(magnitude)-magnitude;
}
float _flw_farLayerNext(float value,bool upward) {
    if(value==0.0) return uintBitsToFloat(upward?1u:0x80000001u);
    uint step=(value>0.0)==upward?1u:0xffffffffu;
    return uintBitsToFloat(floatBitsToUint(value)+step);
}
vec2 _flw_farLayerTimeRange(vec2 time) {
    return vec2(_flw_farLayerNext(time.x-time.y,false),_flw_farLayerNext(time.x+time.y,true));
}
vec2 _flw_farLayerCombineTime(vec2 left,vec2 right,bool latest) {
    vec2 a=_flw_farLayerTimeRange(left),b=_flw_farLayerTimeRange(right);
    float value=latest?max(left.x,right.x):min(left.x,right.x);
    vec2 bounds=latest?max(a,b):min(a,b);
    return vec2(value,_flw_farLayerUpper(max(value-bounds.x,bounds.y-value)));
}
vec2 _flw_farLayerBoundaryTime(float boundary,float origin,float direction) {
    precise float difference=boundary-origin;
    precise float time=difference/direction;
    float error=_flw_farLayerUpper(_flw_farLayerUpper(_flw_farLayerUlp(difference)/abs(direction))
            +_flw_farLayerUlp(time));
    return vec2(time,error);
}
float _flw_farLayerDot(vec3 left,vec3 right,out float error) {
    precise vec3 product=left*right;
    precise float first=product.x+product.y;
    precise float value=first+product.z;
    error=_flw_farLayerUpper(_flw_farLayerUpper(_flw_farLayerUlp(product.x)
            +_flw_farLayerUlp(product.y))+_flw_farLayerUlp(product.z));
    error=_flw_farLayerUpper(_flw_farLayerUpper(error+_flw_farLayerUlp(first))+_flw_farLayerUlp(value));
    return value;
}
dvec2 _flw_farLayerRefinedDot(vec3 left,vec3 right) {
    // Float operands have exact double products. Error-free sums retain cancellation before the final rounding.
    precise dvec3 product=dvec3(left)*dvec3(right);
    precise double first=product.x+product.y;
    precise double back=first-product.x;
    precise double firstError=(product.x-(first-back))+(product.y-back);
    precise double second=first+product.z;
    precise double last=second-first;
    precise double secondError=(first-(second-last))+(product.z-last);
    precise double low=firstError+secondError;
    precise double value=second+low;
    double error=2.220446049250313e-16lf*(abs(low)+abs(value))*4.0lf;
    return dvec2(value,error);
}
bool _flw_farLayerPlaneTime(vec4 plane,FarLayerRay ray,out vec2 result) {
    float originError,directionError;
    float originDot=_flw_farLayerDot(plane.xyz,ray.origin,originError);
    float directionDot=_flw_farLayerDot(plane.xyz,ray.direction,directionError);
    precise float numerator=originDot+plane.w;
    float numeratorError=_flw_farLayerUpper(originError+_flw_farLayerUlp(numerator));
    if(abs(directionDot)>directionError) {
        precise float time=-numerator/directionDot;
        float minimumDenominator=abs(directionDot)-directionError;
        // One more outward step covers rounding the positive denominator toward zero.
        minimumDenominator=uintBitsToFloat(floatBitsToUint(minimumDenominator)-1u);
        float error=_flw_farLayerUpper(_flw_farLayerUpper(numeratorError
                +_flw_farLayerUpper(abs(time)*directionError))/minimumDenominator);
        result=vec2(time,_flw_farLayerUpper(error+_flw_farLayerUlp(time)));
        return true;
    }
    // Nearly parallel planes need a refined dot product before dividing; the ordinary path stays float-only.
    dvec2 denominator=_flw_farLayerRefinedDot(plane.xyz,ray.direction);
    if(denominator.x==0.0) return false;
    dvec2 origins=_flw_farLayerRefinedDot(plane.xyz,ray.origin);
    precise double offset=origins.x+double(plane.w);
    double offsetError=origins.y+abs(offset)*2.220446049250313e-16lf*2.0lf;
    precise double time=-offset/denominator.x;
    float rounded=float(time);
    double error=(offsetError+abs(time)*denominator.y)/(abs(denominator.x)-denominator.y)
            +abs(time)*2.220446049250313e-16lf*2.0lf+abs(time-double(rounded));
    result=vec2(rounded,_flw_farLayerUpper(float(error)+_flw_farLayerUlp(rounded)));
    return true;
}
bool _flw_farLayerPosition(FarLayerRay ray,vec4 plane,vec2 time,out vec3 position) {
    position=fma(ray.direction,vec3(time.x),ray.origin);
    vec3 error;
    for(int a=0;a<3;a++) error[a]=_flw_farLayerUpper(
            _flw_farLayerUpper(abs(ray.direction[a])*time.y)+_flw_farLayerUlp(position[a]));
    uint axis=_flw_farLayerAxis(plane.xyz),b=(axis+1u)%3u,c=(axis+2u)%3u;
    precise float first=fma(plane[c],position[c],plane.w);
    precise float remainder=fma(plane[b],position[b],first);
    position[axis]=-remainder/plane[axis];
    float remainderError=_flw_farLayerUpper(_flw_farLayerUpper(abs(plane[b])*error[b])
            +_flw_farLayerUpper(abs(plane[c])*error[c]));
    remainderError=_flw_farLayerUpper(_flw_farLayerUpper(remainderError+_flw_farLayerUlp(first))
            +_flw_farLayerUlp(remainder));
    error[axis]=_flw_farLayerUpper(_flw_farLayerUpper(remainderError/abs(plane[axis]))
            +_flw_farLayerUlp(position[axis]));
    if(plane[b]==0.0&&plane[c]==0.0) {
        if(plane.w==0.0) { position[axis]=0.0;error[axis]=0.0; }
        else if(plane.w==-plane[axis]*ray.clipExtent[axis]) {
            position[axis]=ray.clipExtent[axis];error[axis]=0.0;
        }
    }
    for(int a=0;a<3;a++) {
        vec2 bounds=_flw_farLayerTimeRange(vec2(position[a],error[a]));
        if(bounds.x<=0.0&&bounds.y>=0.0) position[a]=0.0;
        else if(bounds.x<=ray.clipExtent[a]&&bounds.y>=ray.clipExtent[a]) position[a]=ray.clipExtent[a];
    }
    return _flw_farLayerOwns(position,ray.clipExtent,ray.ownedMax);
}
ivec3 _flw_farLayerTexel(FarLayeredTile tile, uint rank, uint face, ivec2 pixel, uint level,
                        uvec3 extent) {
    uint axis=face>>1u;
    uint first=_flw_layerRects[tile.records.x+level].x;
    uint address=_flw_layerRects[first+rank][axis];
    ivec3 at=ivec3(ivec2(address&1023u,(address>>10u)&1023u),int(address>>20u));
    if((face&1u)!=0u) at.x+=int(extent[(axis+1u)%3u]>>level)+2;
    at.xy+=pixel+1;
    return at;
}
uint _flw_farLayerRanks(FarLayeredTile tile,uint level) {
    return _flw_layerRects[tile.records.x+level].y;
}
vec2 _flw_farLayerUv(vec3 q, vec3 extent, uint face) {
    uint a=face>>1u;
    return vec2((face&1u)!=0u?extent[(a+1u)%3u]-q[(a+1u)%3u]:q[(a+1u)%3u],
            q[(a+2u)%3u]);
}
bool _flw_farLayerSample(FarLayerRay ray, uint source, vec3 q, uint face, uint level,
                         out FarLayerSample value) {
    FarPart part=_flw_layerParts[ray.partSlot];
    FarLayeredTile tile=_flw_layerTiles[floatBitsToUint(part.boxMin.w)];
    uvec3 extent=uvec3(ray.extent);
    uint a=face>>1u;
    ivec2 size=ivec2(extent[(a+1u)%3u],extent[(a+2u)%3u])>>int(level);
    vec2 point=_flw_farLayerUv(q,ray.extent,face)/float(1u<<level)-0.5;
    ivec2 base=ivec2(floor(point));
    vec2 fraction=fract(point);
    value.color=vec4(0);
    value.normal=vec4(0);
    value.light=vec4(0);
    value.material=uvec4(0);
    vec2 uv=vec2(0);
    vec3 tangentSum=vec3(0);
    float largest=-1.0;
    uint partner=_flw_layerSources[tile.source.x+source-1u].info.z!=0u?source+1u:0u;
    for(int child=0;child<4;child++) {
        ivec2 off=ivec2(child&1,child>>1);
        ivec2 pixel=base+off;
        if(any(lessThan(pixel,ivec2(-1)))||any(greaterThan(pixel,size))) continue;
        vec2 weights=mix(1.0-fraction,fraction,vec2(off));
        float weight=weights.x*weights.y;
        for(uint rank=0u;rank<_flw_farLayerRanks(tile,level);rank++) {
            ivec3 at=_flw_farLayerTexel(tile,rank,face,pixel,level,extent);
            uint id=texelFetch(_flw_layerSurface,at,0).r;
            if(id==0u) break;
            if(id!=source&&id!=partner) continue;
            vec4 light=texelFetch(_flw_layerLight,at,0);
            float coverage=light.w*weight;
            value.color+=texelFetch(_flw_layerAlbedo,at,0)*weight;
            vec4 normal=texelFetch(_flw_layerNormals,at,0);
            value.normal+=vec4(normal.xyz*2.0-1.0,normal.w)*coverage;
            value.light+=vec4(light.xyz,1.0)*coverage;
            uvec4 material=texelFetch(_flw_layerMaterials,at,0);
            uv+=uintBitsToFloat(material.xy)*coverage;
            tangentSum+=unpackSnorm4x8(material.w).xyz*coverage;
            if(coverage>largest) { largest=coverage;value.material=material; }
            // Certified coplanar partners partition one authored quad's footprint. Sum their filter support
            // before returning one alpha/coverage pair; independently source-overing them darkens its diagonal.
            if(partner==0u) break;
        }
    }
    if(value.light.w==0.0) return false;
    value.color/=value.light.w;
    value.normal.xyz=normalize(value.normal.xyz);
    value.normal.w/=value.light.w;
    value.light.xyz/=value.light.w;
    value.material.xy=floatBitsToUint(uv/value.light.w);
    value.material.w=(packSnorm4x8(vec4(normalize(tangentSum),0.0))&0xffffffu)
            |(value.material.w&0xff000000u);
    return true;
}
FarLayerRay _flw_farBeginRay(uint partSlot, vec3 origin, vec3 direction,
                            uint firstSurface, uint surfaceCount) {
    FarPart part=_flw_layerParts[partSlot];
    FarTile tile=_flw_layerOpaqueTiles[part.info.y];
    FarLayeredTile layers=_flw_layerTiles[floatBitsToUint(part.boxMin.w)];
    FarLayerRay ray;
    ray.partSlot=partSlot;
    ray.firstSurface=firstSurface;
    ray.surfaceCount=surfaceCount;
    ray.lastSource=0u;
    ray.ownedMax=(part.info.w>>_FLW_FAR_PART_OWNED_MAX_SHIFT)&7u;
    ray.pivot=part.pivot.xyz;
    ray.rotation=mat3(1);
    if(part.pivot.w!=0.0) {
        float angle=part.axis.w+_flw_farAngle(part.pivot.w,part.origin.w);
        ray.rotation=mat3(_flw_farRotate(vec3(1,0,0),part.axis.xyz,angle),
                _flw_farRotate(vec3(0,1,0),part.axis.xyz,angle),
                _flw_farRotate(vec3(0,0,1),part.axis.xyz,angle));
    }
    ray.offset=vec3(part.origin.xyz-_flw_farLayerRenderOrigin)+ray.pivot;
    ray.boxMin=part.boxMin.xyz;
    ray.extent=vec3(tile.extent.xyz);
    ray.clipExtent=uintBitsToFloat(uvec3(layers.source.zw,layers.records.w));
    ray.density=uintBitsToFloat(tile.pairs.w);
    ray.origin=(transpose(ray.rotation)*(origin-ray.offset)+ray.pivot-ray.boxMin)*ray.density;
    ray.direction=transpose(ray.rotation)*normalize(direction)*ray.density;
    vec2 lo=vec2(0.0),hi=vec2(1e30,0.0);
    for(int a=0;a<3;a++) {
        if(ray.direction[a]==0.0) {
            if(ray.origin[a]<0.0||ray.origin[a]>ray.clipExtent[a]
                    ||ray.origin[a]==ray.clipExtent[a]&&(ray.ownedMax&(1u<<uint(a)))==0u) hi=vec2(-1.0,0.0);
        } else {
            vec2 t0=_flw_farLayerBoundaryTime(0.0,ray.origin[a],ray.direction[a]);
            vec2 t1=_flw_farLayerBoundaryTime(ray.clipExtent[a],ray.origin[a],ray.direction[a]);
            lo=_flw_farLayerCombineTime(lo,ray.direction[a]>0.0?t0:t1,true);
            hi=_flw_farLayerCombineTime(hi,ray.direction[a]>0.0?t1:t0,false);
        }
    }
    ray.cursor=lo.x;ray.cursorError=lo.y;ray.end=hi.x;ray.endError=hi.y;ray.lastTime=-1.0;
    float distanceToBox=max(length(clamp(ray.origin,vec3(0),ray.extent)-ray.origin)/ray.density,1.0);
    float lod=clamp(floor(log2(max(ray.density*distanceToBox/_flw_farLayerPixelScale,1.0))),0.0,4.0);
    while((layers.records.z&(1u<<uint(lod)))==0u&&lod>0.0) lod--;
    ray.lod=uint(lod);
    return ray;
}
FarLayerRay _flw_farBeginRay(uint partSlot,vec3 origin,vec3 direction) {
    FarPart part=_flw_layerParts[partSlot];
    FarLayeredTile tile=_flw_layerTiles[floatBitsToUint(part.boxMin.w)];
    return _flw_farBeginRay(partSlot,origin,direction,1u,tile.source.y);
}
// The inverse matrix uses the same render origin and raw clip convention as the producer view.
FarLayerRay _flw_farBeginPixelRay(uint partSlot,vec2 pixelCenter,vec2 viewport,mat4 inverseViewProjection,
                                 uint firstSurface,uint surfaceCount) {
    vec4 point=inverseViewProjection*vec4(pixelCenter/viewport*2.0-1.0,
            _flw_farLayerZeroToOne?0.5:0.0,1.0);
    return _flw_farBeginRay(partSlot,_flw_farLayerCamera,point.xyz/point.w-_flw_farLayerCamera,
            firstSurface,surfaceCount);
}
// Empty hierarchy cells skip grid intervals. Populated cells refine before plane/footprint tests.
bool _flw_farLayerOccupied(FarLayerRay ray,vec3 q,uint level) {
    FarPart part=_flw_layerParts[ray.partSlot];
    FarLayeredTile tile=_flw_layerTiles[floatBitsToUint(part.boxMin.w)];
    uvec3 extent=uvec3(ray.extent);
    for(uint axis=0u;axis<3u;axis++) {
        uint face=axis*2u+uint(ray.direction[axis]>0.0);
        ivec2 size=ivec2(extent[(axis+1u)%3u],extent[(axis+2u)%3u])>>int(level);
        ivec2 pixel=ivec2(floor(_flw_farLayerUv(q,ray.extent,face)/float(1u<<level)-0.5));
        for(int c=0;c<4;c++) {
            ivec2 at=pixel+ivec2(c&1,c>>1);
            if(any(lessThan(at,ivec2(-1)))||any(greaterThan(at,size))) continue;
            if(_flw_farLayerRanks(tile,level)>0u&&texelFetch(_flw_layerSurface,
                    _flw_farLayerTexel(tile,0u,face,at,level,extent),0).r!=0u) return true;
        }
    }
    return false;
}
vec2 _flw_farLayerCellEnd(FarLayerRay ray,vec3 q,uint level) {
    float size=float(1u<<level);
    vec2 end=vec2(ray.end,ray.endError);
    for(int a=0;a<3;a++) {
        if(ray.direction[a]==0.0) continue;
        float grid=floor(q[a]/size);
        float boundary=(grid+float(ray.direction[a]>0.0))*size;
        vec2 time=_flw_farLayerBoundaryTime(boundary,ray.origin[a],ray.direction[a]);
        if(time.x<=ray.cursor) {
            boundary+=ray.direction[a]>0.0?size:-size;
            time=_flw_farLayerBoundaryTime(boundary,ray.origin[a],ray.direction[a]);
        }
        end=_flw_farLayerCombineTime(end,time,false);
    }
    return end;
}
bool _flw_farNextHit(inout FarLayerRay ray,out FarLayerHit hit) {
    FarPart part=_flw_layerParts[ray.partSlot];
    FarLayeredTile tile=_flw_layerTiles[floatBitsToUint(part.boxMin.w)];
    vec2 endBounds=_flw_farLayerTimeRange(vec2(ray.end,ray.endError));
    while(_flw_farLayerTimeRange(vec2(ray.cursor,ray.cursorError)).x<=endBounds.y) {
        vec3 q=fma(ray.direction,vec3(ray.cursor),ray.origin);
        uint level=ray.lod;
        while(level>0u&&_flw_farLayerOccupied(ray,q,level)) level--;
        vec2 cellEnd=_flw_farLayerCellEnd(ray,q,level);
        vec2 cellBounds=_flw_farLayerTimeRange(cellEnd);
        vec2 cursorBounds=_flw_farLayerTimeRange(vec2(ray.cursor,ray.cursorError));
        float best=1e30;
        vec3 selectedPosition=vec3(0);
        uint selected=0u,selectedFace=0u;
        uvec3 extent=uvec3(ray.extent);
        vec3 cellLast=fma(ray.direction,vec3(cellEnd.x),ray.origin);
        // Discovery and payload use the same mip. A primitive absent from the fine taps can still contribute
        // to its coarse footprint. Include both interval endpoints: a tent's tap set can change inside a DDA cell.
        for(uint axis=0u;axis<3u;axis++) {
            uint face=axis*2u+uint(ray.direction[axis]>0.0);
            ivec2 size=ivec2(extent[(axis+1u)%3u],extent[(axis+2u)%3u])>>int(ray.lod);
            float scale=float(1u<<ray.lod);
            vec2 start=_flw_farLayerUv(q,ray.extent,face)/scale-0.5;
            vec2 finish=_flw_farLayerUv(cellLast,ray.extent,face)/scale-0.5;
            vec2 lo=min(start,finish),hi=max(start,finish);
            // Round tap bounds outward as well: an exact endpoint on a texel boundary must include either side
            // when reconstructing the intersection rounds to its adjacent representable coordinate.
            for(int a=0;a<2;a++) {
                lo[a]=_flw_farLayerNext(lo[a],false);
                hi[a]=_flw_farLayerNext(hi[a],true);
            }
            ivec2 low=max(ivec2(floor(lo)),ivec2(-1)),high=min(ivec2(floor(hi))+1,size);
            for(int y=low.y;y<=high.y;y++) for(int x=low.x;x<=high.x;x++) {
                ivec2 point=ivec2(x,y);
                for(uint rank=0u;rank<_flw_farLayerRanks(tile,ray.lod);rank++) {
                    ivec3 at=_flw_farLayerTexel(tile,rank,face,point,ray.lod,extent);
                    uint source=texelFetch(_flw_layerSurface,at,0).r;
                    if(source==0u) break;
                    uint quad=_flw_layerSources[tile.source.x+source-1u].info.z;
                    if(quad!=0u) source=quad;
                    // Canonical ownership also partitions source windows: a partner outside this window can
                    // supply filter support, but the group is emitted only by the window containing its root.
                    if(source<ray.firstSurface||source-ray.firstSurface>=ray.surfaceCount) continue;
                    FarLayerSurface geometry=_flw_layerSources[tile.source.x+source-1u];
                    if(_flw_farLayerAxis(geometry.plane.xyz)!=axis) continue;
                    if((geometry.info.x&1u)!=0u&&dot(geometry.plane.xyz,ray.direction)>=0.0) continue;
                    vec2 time;
                    if(!_flw_farLayerPlaneTime(geometry.plane,ray,time)) continue;
                    vec2 timeBounds=_flw_farLayerTimeRange(time);
                    if(timeBounds.y<cursorBounds.x||timeBounds.x>cellBounds.y
                            ||timeBounds.x>endBounds.y) continue;
                    if(time.x<ray.lastTime||time.x==ray.lastTime&&source<=ray.lastSource) continue;
                    vec3 position;
                    if(!_flw_farLayerPosition(ray,geometry.plane,time,position)) continue;
                    FarLayerSample sampleValue;
                    if(!_flw_farLayerSample(ray,source,position,face,ray.lod,sampleValue)) continue;
                    if(time.x<best||time.x==best&&source<selected) {
                        best=time.x;selected=source;selectedFace=face;selectedPosition=position;
                    }
                }
            }
        }
        if(selected!=0u) {
            vec3 position=selectedPosition;
            FarLayerSample sampleValue;
            _flw_farLayerSample(ray,selected,position,selectedFace,ray.lod,sampleValue);
            vec3 world=ray.offset+ray.rotation*(ray.boxMin-ray.pivot+position/ray.density);
            vec4 clip=_flw_farLayerViewProjection*vec4(world,1.0);
            float device=clip.z/clip.w;
            if(!_flw_farLayerZeroToOne) device=device*0.5+0.5;
            hit.positionDepth=vec4(world,device);
            hit.albedo=sampleValue.color;
            hit.normalDiffuse=vec4(normalize(ray.rotation*sampleValue.normal.xyz),sampleValue.normal.w);
            vec2 currentLight=vec2(part.info.z&65535u,part.info.z>>16u)/256.0;
            hit.lightCoverage=vec4(max(currentLight,sampleValue.light.xy),sampleValue.light.zw);
            hit.material=sampleValue.material;
            vec4 tangent=unpackSnorm4x8(sampleValue.material.w);
            vec3 worldTangent=normalize(ray.rotation*tangent.xyz);
            hit.material.w=(packSnorm4x8(vec4(worldTangent,0.0))&0xffffffu)|(sampleValue.material.w&0xff000000u);
            hit.tangent=vec4(worldTangent,(sampleValue.material.w&0x01000000u)!=0u?-1.0:1.0);
            hit.identity=uvec4(selected,ray.partSlot,
                    _flw_layerSources[tile.source.x+selected-1u].info.x,0u);
            ray.lastTime=best;ray.lastSource=selected;
            return true;
        }
        if(cellEnd.x>=ray.end) return false;
        // Both neighbouring cells include this time. lastTime/lastSource suppress an already emitted hit;
        // incrementing the time's bit pattern instead skips legitimate planes on the shared boundary.
        ray.cursor=cellEnd.x;
        ray.cursorError=cellEnd.y;
    }
    return false;
}
