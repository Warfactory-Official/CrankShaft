#version 430 core
uniform sampler2D saved7Main;
uniform sampler2D saved7Alt;
#ifdef FLW_SEUS_RESTORE_COLOR0
uniform sampler2D saved0Main;
uniform sampler2D saved0Alt;
layout(location = 0) out vec4 color0Main;
layout(location = 1) out vec4 color0Alt;
layout(location = 2) out vec4 color7Main;
layout(location = 3) out vec4 color7Alt;
#else
layout(location = 0) out vec4 color7Main;
layout(location = 1) out vec4 color7Alt;
#endif
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    #ifdef FLW_SEUS_RESTORE_COLOR0
    color0Main = texelFetch(saved0Main, pixel, 0);
    color0Alt = texelFetch(saved0Alt, pixel, 0);
    #endif
    color7Main = texelFetch(saved7Main, pixel, 0);
    color7Alt = texelFetch(saved7Alt, pixel, 0);
}
