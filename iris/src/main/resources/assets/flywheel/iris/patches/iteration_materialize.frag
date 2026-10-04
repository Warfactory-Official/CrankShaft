#version 430 core
uniform sampler2D saved0;
uniform sampler2D saved1;
uniform sampler2D saved2;
uniform sampler2D saved3;
uniform sampler2D saved4;
uniform sampler2D saved5;
uniform sampler2D savedDepth;
uniform sampler2D behind;
uniform sampler2D behindDepth;
uniform sampler2D initialLighting;
uniform sampler2D baseLighting;
uniform int width;
uniform int layer;
layout(r32ui, binding=0) uniform uimage2D opaqueDepth;
layout(r32ui, binding=1) uniform uimage2D waterDepth;
#ifdef FLW_FAST_MATERIALIZE
vec4 data0, data1, data2, lighting;
layout(location=0) out vec4 data3;
layout(location=1) out vec4 data4;
layout(location=2) out vec4 data5;
layout(location=3) out uint layerNode;
#else
layout(location=0) out vec4 data0;
layout(location=1) out vec4 data1;
layout(location=2) out vec4 data2;
layout(location=3) out vec4 data3;
layout(location=4) out vec4 data4;
layout(location=5) out vec4 data5;
layout(location=6) out uint layerNode;
layout(location=7) out vec4 lighting;
#endif
void main() {
    bool fast = flw_overflow==0u && flw_maxLayers>1u && uint(layer)!=flw_maxLayers-1u
            && (flw_tiles[layer]&1u)==0u;
    #ifdef FLW_FAST_MATERIALIZE
    if(!fast) discard;
    #else
    if(fast) discard;
    #endif
    ivec2 p = ivec2(gl_FragCoord.xy);
    uint pixel = uint(p.y * width + p.x);
    data0 = texelFetch(saved0,p,0); data1 = texelFetch(saved1,p,0); data2 = texelFetch(saved2,p,0);
    data3 = texelFetch(saved3,p,0); data4 = texelFetch(saved4,p,0); data5 = texelFetch(saved5,p,0);
    gl_FragDepth = texelFetch(savedDepth,p,0).r;
    layerNode=0u;
    lighting=texelFetch(initialLighting,p,0);
    if (flw_overflow != 0u || flw_maxLayers <= 1u) return;
    uvec4 a = flw_opaque[pixel * 2u], b = flw_opaque[pixel * 2u + 1u];
    data0 = vec4(unpackHalf2x16(a.x),unpackHalf2x16(a.y));
    data1 = vec4(unpackUnorm2x16(a.z),unpackUnorm2x16(a.w));
    data2 = vec4(unpackUnorm2x16(b.x),unpackUnorm2x16(b.y));
    data3 = data1; data4 = data2; data5 = vec4(0.0);
    float depth = uintBitsToFloat(b.z);
    imageStore(opaqueDepth,p,uvec4(b.z));
    bool base = uint(layer) == flw_maxLayers - 1u;
    if(!base) lighting=texelFetch(baseLighting,p,0);
    uint node = base ? 0xffffffffu : flw_materialAt(pixel,layer);
    layerNode=base?0xffffffffu:node==0xffffffffu?0u:node+1u;
    if (node != 0xffffffffu) {
        uvec4 h = flw_nodes[node], d = flw_nodes[node+1u], w = flw_nodes[node+2u];
        data0 = vec4(unpackHalf2x16(h.z),unpackHalf2x16(h.w));
        data3 = vec4(unpackUnorm2x16(d.x),unpackUnorm2x16(d.y));
        data4 = vec4(unpackUnorm2x16(d.z),unpackUnorm2x16(d.w));
        data5 = vec4(unpackUnorm2x16(w.x),unpackUnorm2x16(w.y));
        depth = uintBitsToFloat(h.y);
        uint kind = uint(round(data3.z * 65535.0)) & 255u;
        bool solid = kind >= 100u && kind <= 104u;
        if (solid) { data1 = data3; data2 = data4; }
        else { data0 = vec4(unpackHalf2x16(a.x),unpackHalf2x16(a.y)); }
        imageStore(opaqueDepth,p,uvec4(floatBitsToUint(solid ? depth : 1.0-texelFetch(behindDepth,p,0).r)));
        if (w.z != 0u) {
            imageStore(waterDepth,p,uvec4(w.z));
            if (imageSize(waterDepth).y > imageSize(opaqueDepth).y)
                imageStore(waterDepth,p+ivec2(0,imageSize(opaqueDepth).y),uvec4(w.w));
        }
    }
    gl_FragDepth = 1.0-depth;
}
