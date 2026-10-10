#version 430 core
uniform sampler2D saved7[2];
#ifdef FLW_SEUS_RESTORE_COLOR0
uniform sampler2D saved0[2];
layout(location = 0) out vec4 color0Main;
layout(location = 1) out vec4 color0Alt;
layout(location = 2) out vec4 color7Main;
layout(location = 3) out vec4 color7Alt;
#else
layout(location = 0) out vec4 color7Main;
layout(location = 1) out vec4 color7Alt;
#endif
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
#ifdef FLW_SEUS_RESTORE_COLOR0
    color0Main = texelFetch(saved0[0], p, 0);
    color0Alt = texelFetch(saved0[1], p, 0);
#endif
    color7Main = texelFetch(saved7[0], p, 0);
    color7Alt = texelFetch(saved7[1], p, 0);
}
