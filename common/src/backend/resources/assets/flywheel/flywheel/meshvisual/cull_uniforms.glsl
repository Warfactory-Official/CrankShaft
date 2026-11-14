struct FrustumPlanes {
    vec4 xyX;
    vec4 xyY;
    vec4 xyZ;
    vec4 xyW;
    vec2 zX;
    vec2 zY;
    vec2 zZ;
    vec2 zW;
};
struct _FlwCullData {
    float znear;
    float zfar;
    float P00;
    float P11;
    float viewWidth;
    float viewHeight;
    int pyramidLevels;
    uint useMin;
};
layout(std140, binding = 8) uniform _FlwFrameUniforms {
    FrustumPlanes flw_frustumPlanes;
    _FlwCullData _flw_cullData;
    mat4 flw_view;
};
