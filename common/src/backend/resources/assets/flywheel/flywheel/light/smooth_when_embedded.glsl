#include "flywheel:internal/lighting_factors.glsl"
#define _FLW_TRACKED_LIGHTING

void flw_shaderLight() {
    #ifdef FLW_EMBEDDED
    FlwLightAo light;
    if (flw_light(flw_vertexPos.xyz, flw_vertexNormal, light)) {
        flw_fragLight = max(flw_fragLight, light.light);

        if (flw_aoEnabled()) {
            flw_applyAo(light.ao);
        }
    }
    #endif
}
