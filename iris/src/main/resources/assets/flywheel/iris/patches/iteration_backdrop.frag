#version 430 core
uniform sampler2D behind;
uniform int width;
uniform int layer;
layout(location=0) out vec4 color;
void main() {
    if (flw_overflow != 0u || flw_maxLayers <= 1u || uint(layer)==flw_maxLayers-1u) discard;
    ivec2 p=ivec2(gl_FragCoord.xy);
    uint node=flw_materialAt(uint(p.y*width+p.x),layer);
    if (node!=0xffffffffu) {
        uint kind=uint(round(unpackUnorm2x16(flw_nodes[node+1u].y).x*65535.0))&255u;
        if (kind>=100u&&kind<=104u) discard;
    }
    color=texelFetch(behind,p,0);
}
