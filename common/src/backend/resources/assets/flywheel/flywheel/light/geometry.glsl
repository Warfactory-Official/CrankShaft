#include "flywheel:internal/geometry_ao.glsl"
#include "flywheel:internal/lighting_factors.glsl"
#define _FLW_TRACKED_LIGHTING

void flw_shaderLight() {
    vec3 normal = gl_FrontFacing ? flw_vertexNormal : -flw_vertexNormal;
    FlwLightAo light;
    if (flw_light(flw_vertexPos.xyz, normal, light)) {
        flw_fragLight = max(flw_fragLight, light.light + vec2(.5 / 16.));
    }
    if (flw_aoEnabled()) {
        flw_applyAo(flw_geometryOcclusion(flw_vertexPos.xyz, normal, 2., 1e-4, 64u));
    }
}
