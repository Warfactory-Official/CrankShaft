#version 430 core
uniform sampler2D depth;
uniform sampler2D color;
layout(location=0) out vec4 outputColor;
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    gl_FragDepth = texelFetch(depth, pixel, 0).r;
    outputColor = texelFetch(color, pixel, 0);
}
