#include "flywheel:internal/far/far.glsl"
layout(std430,binding=15) readonly buffer FarLayerParts { FarPart _flw_layerParts[]; };
layout(std430,binding=12) readonly buffer FarLayerTiles { FarTile tiles[]; };
layout(std430,binding=5) readonly buffer FarLayerLevels { uint entryLevels[]; };
layout(std430,binding=11) readonly buffer FarLayerSelected { uint selected[]; };
uniform mat4 _flw_farLayerViewProjection;
uniform vec2 _flw_farLayerDepthTransform;
uniform ivec3 _flw_farLayerRenderOrigin;
uniform uint _flw_farLayerPart;
uniform bool _flw_farLayerSingle;
flat out uint _flw_farLayerPartSlot;
out vec3 _flw_farLayerWorld;
const vec2 corners[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(1,1),vec2(0,0),vec2(1,1),vec2(0,1));
void main() {
    uint slot=_flw_farLayerSingle?_flw_farLayerPart:selected[gl_InstanceID];
    FarPart part=_flw_layerParts[slot];
    _flw_farLayerPartSlot=slot;
    if((part.info.w&3u)!=3u||entryLevels[part.info.x]!=_FLW_FAR_LEVEL_IMPOSTOR
            ||floatBitsToUint(part.boxMin.w)==0xffffffffu) { gl_Position=vec4(0);return; }
    FarTile tile=tiles[part.info.y];
    vec3 extent=vec3(tile.extent.xyz);
    uint face=uint(gl_VertexID)/6u,axis=face>>1u;
    bool negative=(face&1u)!=0u;
    vec2 uv=corners[gl_VertexID%6];if(negative) uv=uv.yx;
    vec3 q;
    q[axis]=negative?0.0:extent[axis];
    q[(axis+1u)%3u]=uv.x*extent[(axis+1u)%3u];
    q[(axis+2u)%3u]=uv.y*extent[(axis+2u)%3u];
    vec3 position=part.boxMin.xyz+q/uintBitsToFloat(tile.pairs.w);
    if(part.pivot.w!=0.0) position=part.pivot.xyz+_flw_farRotate(position-part.pivot.xyz,part.axis.xyz,
            part.axis.w+_flw_farAngle(part.pivot.w,part.origin.w));
    position+=vec3(part.origin.xyz-_flw_farLayerRenderOrigin);
    _flw_farLayerWorld=position;
    gl_Position=_flw_farLayerViewProjection*vec4(position,1);
    gl_Position.z=gl_Position.z*_flw_farLayerDepthTransform.x+gl_Position.w*_flw_farLayerDepthTransform.y;
}
