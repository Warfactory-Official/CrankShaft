#version 430 core
uniform sampler2D depth;
void main() {
    gl_FragDepth = texelFetch(depth, ivec2(gl_FragCoord.xy), 0).r;
}
