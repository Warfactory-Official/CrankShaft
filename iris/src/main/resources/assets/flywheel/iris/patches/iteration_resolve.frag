#version 430 core
uniform sampler2D accumulated;
uniform int width;
uniform int layer;
layout(location=0) out vec4 color;
void main() {
    if (flw_overflow!=0u||flw_maxLayers<=1u||uint(layer)>=flw_maxLayers) discard;
    ivec2 p=ivec2(gl_FragCoord.xy);
    color=texelFetch(accumulated,p,0);
}
