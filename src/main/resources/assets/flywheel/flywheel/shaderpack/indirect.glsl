#include "flywheel:internal/indirect/draw_command.glsl"
#include "flywheel:internal/indirect/matrices.glsl"

layout(std430, binding = 2) restrict readonly buffer _FlwGuestVisibleInstances {
    uint _flw_instanceIndices[];
};
layout(std430, binding = 4) restrict readonly buffer _FlwGuestDraws {
    MeshDrawCommand _flw_drawCommands[];
};
#ifdef FLW_EMBEDDED
layout(std430, binding = 7) restrict readonly buffer _FlwGuestMatrices {
    Matrices _flw_matrices[];
};
#endif
uniform uint _flw_baseDraw;
#if __VERSION__ < 460
#define _flw_drawId gl_DrawIDARB
#else
#define _flw_drawId gl_DrawID
#endif
