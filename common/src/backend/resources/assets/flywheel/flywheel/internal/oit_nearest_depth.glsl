// Inverse of _flw_writeDepthRange .r.
float _flw_nearestDeviceDepth(float r) {
    vec4 clip = ProjMat * vec4(0., 0., r - 1e-5, 1.);
    #ifdef _FLW_DEPTH_ZERO_TO_ONE
    return clip.z / clip.w;
    #else
    return clip.z / clip.w * .5 + .5;
    #endif
}
