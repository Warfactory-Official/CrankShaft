#version 430 core
uniform sampler2D color;
uniform sampler2D weather;
layout(location=0) out vec4 savedColor;
layout(location=1) out vec4 originalWeather;
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    savedColor = texelFetch(color, p, 0);
    originalWeather = texelFetch(weather, p, 0);
}
