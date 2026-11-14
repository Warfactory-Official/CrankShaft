#version 430 compatibility
layout(location = 0) out vec4 forwardLighting;
in vec3 _flw_forwardSunValue;
in vec3 _flw_forwardSkyValue;
#ifndef FLW_FORWARD_OVERWORLD
uniform vec3 fogColor;
#endif
void main() {
    vec3 sun;
    vec3 sky;
    #ifdef FLW_FORWARD_OVERWORLD
    sun = _flw_forwardSunValue;
    sky = _flw_forwardSkyValue;
    #else
    sun = vec3(0.0);
    sky = max(fogColor, vec3(0.03));
    #endif
    forwardLighting = vec4(gl_FragCoord.x < 1.0 ? sun : sky, 1.0);
}
/* RENDERTARGETS: 15 */
