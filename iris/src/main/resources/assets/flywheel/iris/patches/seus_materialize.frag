#version 430 core
layout(std430, binding = 0) readonly buffer Heads { uint heads[]; };
layout(std430, binding = 1) readonly buffer Nodes { uvec4 nodes[]; };
layout(std430, binding = 2) readonly buffer Counts { uint count; uint overflow; uint maxLayers; uint pad; };
uniform sampler2D saved1;
uniform sampler2D saved2;
uniform sampler2D savedDepth;
uniform sampler2D savedOpaque;
uniform sampler2D backColor;
uniform sampler2D backDepth;
#ifdef FLW_SEUS_MOTION
uniform sampler2D savedMotion;
layout(location = 2) out vec3 motion;
#endif
uniform int width;
uniform int layerIndex;
layout(location = 0) out vec4 material;
layout(location = 1) out vec4 attributes;
layout(location = 3) out uint layerNode;
layout(location = 4) out vec4 materialMirror;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(saved1, 0);
    ivec2 halfOrigin = size / 2 + 1;
    bool shaded = all(greaterThanEqual(p, halfOrigin));
    ivec2 q = shaded ? p - halfOrigin : p;
    uint node = overflow == 0u ? heads[uint(q.y * width + q.x)] : 0xffffffffu;
    for (int i = 0; i < layerIndex && node != 0xffffffffu; ++i) node = nodes[node].x;
    if (node != 0xffffffffu && uintBitsToFloat(nodes[node].y) >= 1.0 - texelFetch(savedOpaque, q, 0).r)
        node = 0xffffffffu;
    layerNode = node == 0xffffffffu ? 0u : node + 1u;
    material = shaded ? texelFetch(backColor, p, 0) : texelFetch(saved1, p, 0);
    materialMirror = material;
    attributes = texelFetch(saved2, p, 0);
    gl_FragDepth = layerIndex == 0 ? texelFetch(savedDepth, p, 0).r : texelFetch(backDepth, p, 0).r;
#ifdef FLW_SEUS_MOTION
    motion = texelFetch(savedMotion, p, 0).xyz;
#endif
    if (shaded || node == 0xffffffffu) return;
    uvec4 a = nodes[node], b = nodes[node + 1u];
    material = vec4(unpackUnorm2x16(a.z), unpackUnorm2x16(a.w));
    materialMirror = material;
    attributes = vec4(unpackUnorm2x16(b.x), unpackUnorm2x16(b.y));
    gl_FragDepth = 1.0 - uintBitsToFloat(a.y);
#ifdef FLW_SEUS_MOTION
    motion = vec3(unpackHalf2x16(b.z), unpackHalf2x16(b.w).x);
#endif
}
