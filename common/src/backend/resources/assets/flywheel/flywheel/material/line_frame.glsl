struct _FlwLineFrustumPlanes {
    vec4 xyX;
    vec4 xyY;
    vec4 xyZ;
    vec4 xyW;
    vec2 zX;
    vec2 zY;
    vec2 zZ;
    vec2 zW;
};

struct _FlwLineCullData {
    float znear;
    float zfar;
    float P00;
    float P11;
    float viewWidth;
    float viewHeight;
    int pyramidLevels;
    uint useMin;
};

// Layout matches FrameUniforms; distinct member names avoid dynamic-transform and mesh-frame collisions.
layout(std140, binding = 8) uniform _FlwLineFrameUniforms {
    _FlwLineFrustumPlanes _flw_lineFrustumPlanes;
    _FlwLineCullData _flw_lineCullData;

    mat4 _flw_lineView;
    mat4 _flw_lineViewInverse;
    mat4 _flw_lineViewPrev;
    mat4 _flw_lineProjection;
    mat4 _flw_lineProjectionInverse;
    mat4 _flw_lineProjectionPrev;
    mat4 _flw_lineViewProjection;
    mat4 _flw_lineViewProjectionInverse;
    mat4 _flw_lineViewProjectionPrev;

    ivec4 _flw_lineRenderOrigin;

    vec4 _flw_lineCameraPos;
    vec4 _flw_lineCameraPosPrev;
    vec4 _flw_lineCameraLook;
    vec4 _flw_lineCameraLookPrev;
    vec2 _flw_lineCameraRot;
    vec2 _flw_lineCameraRotPrev;

    vec2 _flw_lineViewportSize;
    float _flw_lineAspectRatio;
    float _flw_lineDefaultWidth;
    float _flw_lineViewDistance;

    uint _flw_lineTicks;
    float _flw_linePartialTick;
    float _flw_lineRenderTicks;
    float _flw_lineRenderSeconds;
    float _flw_lineSystemSeconds;
    uint _flw_lineSystemMillis;

    uint _flw_lineCameraInFluid;
    uint _flw_lineCameraInBlock;
    uint _flw_lineDebugMode;
    float _flw_lineOitNoise;
};
