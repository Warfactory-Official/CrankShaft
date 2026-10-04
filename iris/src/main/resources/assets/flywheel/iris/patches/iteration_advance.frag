#version 430 core
uniform sampler2D resolved;
uniform sampler2D behind;
uniform sampler2D frontDepth;
uniform sampler2D backDepth;
uniform int width;
uniform int layer;
layout(location=0) out vec4 color;
void main() {
    if (flw_overflow != 0u || flw_maxLayers <= 1u) discard;
    ivec2 p=ivec2(gl_FragCoord.xy);
    bool base=uint(layer)==flw_maxLayers-1u;
    uint node=base?0xffffffffu:flw_materialAt(uint(p.y*width+p.x),layer);
    if (!base && node==0xffffffffu) discard;
    color=texelFetch(resolved,p,0);
    gl_FragDepth=texelFetch(frontDepth,p,0).r;
    if (node!=0xffffffffu) {
        uint kind=uint(round(unpackUnorm2x16(flw_nodes[node+1u].y).x*65535.0))&255u;
        if (kind>=100u&&kind<=104u) {
            float alpha=unpackHalf2x16(flw_nodes[node].w).y;
            vec4 back=texelFetch(behind,p,0);
            back.rgb=flw_resolveLight(back.rgb,p,uintBitsToFloat(flw_nodes[node].y),1.0-texelFetch(backDepth,p,0).r,uint(width));
            color.rgb=mix(back.rgb,color.rgb,alpha);
        }
    }
}
