#version 400 compatibility

#include "/include/global.glsl"
#include "/include/utility/color.glsl"
#include "/include/lighting/colors/blocklight_color.glsl"

in vec2 uv;
in vec4 tint;
uniform sampler2D gtexture;

layout(location = 0) out vec4 guestLight;

void main() {
    vec4 color;
    float coverage;
#ifdef CRANKSHAFT_NATIVE_ADDITIVE
#ifdef CRANKSHAFT_NATIVE_COLOR
    color = tint;
#else
    color = texture(gtexture, uv) * tint;
#endif
    coverage = tint.a;
#else
    vec2 light;
    float ao;
    vec4 overlay;
    clrwl_computeFragment(texture(gtexture, uv), color, light, ao, overlay);
    coverage = _flw_emissionCoverage();
#endif
    if (color.a <= 0.0 || coverage <= 0.0) discard;
    guestLight = vec4(srgb_eotf_inv(color.rgb * (color.a / coverage)) * rec709_to_working_color * emission_scale * coverage, 0.0);
}

#ifdef CRANKSHAFT_NATIVE_ADDITIVE
void _flw_nativeAdditive() {}
#endif

/* RENDERTARGETS: 17 */
