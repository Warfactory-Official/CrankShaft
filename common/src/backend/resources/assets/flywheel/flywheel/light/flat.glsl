#include "flywheel:internal/lighting_factors.glsl"
#define _FLW_TRACKED_LIGHTING

void flw_shaderLight() {
    vec2 embeddedLight;
    if (flw_lightFetch(ivec3(floor(flw_vertexPos.xyz)) + flw_renderOrigin, embeddedLight)) {
        flw_fragLight = max(flw_fragLight, embeddedLight);
    }
}
