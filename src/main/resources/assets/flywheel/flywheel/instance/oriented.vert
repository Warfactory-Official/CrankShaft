#include "flywheel:util/quaternion.glsl"

void flw_instanceVertex(in FlwInstance i) {
    flw_vertexPos = vec4(rotateByQuaternion(flw_vertexPos.xyz - i.pivot, i.rotation) + i.pivot + i.position, 1.0);
    flw_vertexNormal = rotateByQuaternion(flw_vertexNormal, i.rotation);
    flw_vertexColor *= i.color;
    flw_vertexOverlay = i.overlay;
    // Some drivers have a bug where uint over float division is invalid, so use an explicit cast.
    // Half-texel offset to match vanilla's lightmap UV (see vertex_input.vert comment).
    flw_vertexLight = max((vec2(i.light) + 8.0) / 256.0, flw_vertexLight);
}
