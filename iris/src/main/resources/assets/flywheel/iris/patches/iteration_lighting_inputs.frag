#version 430 core
uniform sampler2D lighting;
uniform sampler2D weights;
uniform int layer;
layout(location=0) out vec4 diffuse;
layout(location=1) out vec4 variance;
void main() {
    if(flw_overflow==0u && flw_maxLayers>1u && uint(layer)!=flw_maxLayers-1u && (flw_tiles[layer]&1u)==0u) discard;
    ivec2 p=ivec2(gl_FragCoord.xy);
    diffuse=texelFetch(lighting,p,0);
    variance=texelFetch(weights,p,0);
}
