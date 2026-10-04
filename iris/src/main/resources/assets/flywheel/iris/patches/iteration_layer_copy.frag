#version 430 core
uniform sampler2D accumulated;
uniform sampler2D backDepth;
uniform usampler2D savedOpaque;
uniform usampler2D savedWater;
uniform sampler2D savedPrevious;
uniform int height;
layout(r32ui, binding=1) uniform writeonly uimage2D waterDepth;
layout(location=0) out vec4 background;
layout(location=1) out uint opaqueDepth;
layout(location=2) out float previousDepth;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    background = texelFetch(accumulated, p, 0);
    gl_FragDepth = texelFetch(backDepth, p, 0).r;
    opaqueDepth = texelFetch(savedOpaque, p, 0).r;
    previousDepth = texelFetch(savedPrevious, p, 0).r;
    for (int y=p.y; y<imageSize(waterDepth).y; y+=height) {
        ivec2 row = ivec2(p.x, y);
        imageStore(waterDepth, row, texelFetch(savedWater, row, 0));
    }
}
