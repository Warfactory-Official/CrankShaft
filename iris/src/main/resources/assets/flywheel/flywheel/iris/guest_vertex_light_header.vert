// Inputs of the material's light shader, run per vertex: Sodium lights and occludes terrain per vertex too.
struct FlwLightAo {
    vec2 light;
    float ao;
};

#include "flywheel:internal/render_origin.glsl"

vec4 flw_fragColor;
vec2 flw_fragLight;
FlwMaterial flw_material;
