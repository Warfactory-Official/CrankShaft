#version 430 core
uniform sampler2D _flw_depthRange;
void main() {
    float depth = texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0).b;
    if (depth <= 0.0) discard;
    #ifdef FLW_OIT_SHADOW_DEPTH
    gl_FragDepth = 1.0 - depth;
    #else
    gl_FragDepth = depth;
    #endif
}
