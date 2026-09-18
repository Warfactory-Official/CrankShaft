#version 430 core
layout(std430, binding=0) readonly buffer Heads { uint heads[]; };
layout(std430, binding=1) readonly buffer Nodes { uvec4 nodes[]; };
layout(std430, binding=2) readonly buffer Counts { uint count; uint overflow; uint maxLayers; uint pad; };
uniform sampler2D saved0;
uniform sampler2D saved1;
uniform sampler2D saved2;
uniform sampler2D savedDepth;
uniform sampler2D saved5;
uniform sampler2D savedCloud;
uniform sampler2D backDepth;
uniform int width;
uniform int layerIndex;
layout(location=0) out vec4 data0;
layout(location=1) out vec4 data1;
layout(location=2) out vec4 data2;
layout(location=3) out vec4 data5;
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    data0 = texelFetch(saved0, pixel, 0);
    data1 = texelFetch(saved1, pixel, 0);
    data2 = texelFetch(saved2, pixel, 0);
    data5 = texelFetch(saved5, pixel, 0);
    data5.a = texelFetch(savedCloud, pixel, 0).a;
    gl_FragDepth = texelFetch(savedDepth, pixel, 0).r;
    if (overflow != 0u) return;
    uint head = heads[uint(pixel.y * width + pixel.x)];
    uint nearest = 0xffffffffu;
    float depth = 1.0;

    for (int i = 0; i < layerIndex && head != 0xffffffffu; ++i) head = nodes[head * 2u].x;
    nearest = head;
    if (head != 0xffffffffu) depth = uintBitsToFloat(nodes[head * 2u].y);
    else if (layerIndex > 0) gl_FragDepth = texelFetch(backDepth, pixel, 0).r;
    if (nearest == 0xffffffffu) return;
    uvec4 a = nodes[nearest * 2u];
    uvec4 b = nodes[nearest * 2u + 1u];
    data0 = unpackUnorm4x8(a.z);
    data1 = vec4(unpackSnorm2x16(a.w), unpackSnorm2x16(b.x));
    data2 = vec4(unpackUnorm2x16(b.y), unpackUnorm2x16(b.z));
    data5.a = uintBitsToFloat(b.w);
    gl_FragDepth = 1.0 - depth;
}
