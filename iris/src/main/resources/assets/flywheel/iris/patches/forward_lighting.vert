out vec3 _flw_forwardSunValue;
out vec3 _flw_forwardSkyValue;
void main() {
    _flw_forwardLightMain();
    #ifdef FLW_FORWARD_OVERWORLD
    #ifdef FLW_FORWARD_SUNDIAL
    _flw_forwardSunValue = sunColor;
    _flw_forwardSkyValue = skyColorUp;
    #else
    #ifdef FLW_FORWARD_ITERATION
    _flw_forwardSunValue = colorShadowlight;
    _flw_forwardSkyValue = colorSkylight;
    #else
    _flw_forwardSunValue = colorSunlight;
    _flw_forwardSkyValue = colorSkyUp;
    #endif
    #endif
    #else
    _flw_forwardSunValue = vec3(0.0);
    _flw_forwardSkyValue = vec3(0.0);
    #endif
    #ifdef FLW_FORWARD_SEUS
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    #endif
}
