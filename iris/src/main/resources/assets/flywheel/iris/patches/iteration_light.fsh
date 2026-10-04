#version 430 compatibility

#include "/Lib/Settings.glsl"

in vec2 v_texCoord;
#ifdef CRANKSHAFT_NATIVE_ADDITIVE
in vec4 v_color;
#endif

uniform sampler2D tex;

layout(location = 0) out vec4 guestEmission;
layout(location = 1) out vec4 guestDisplay;

void main() {
    vec4 color;
    float coverage;
    vec2 light;
    float ao;
    vec4 overlay;
#ifdef CRANKSHAFT_NATIVE_ADDITIVE
#ifdef CRANKSHAFT_NATIVE_COLOR
    color = v_color;
#else
    color = texture(tex, v_texCoord) * v_color;
#endif
    coverage = v_color.a;
#else
    clrwl_computeFragment(texture(tex, v_texCoord), color, light, ao, overlay);
    coverage = _flw_emissionCoverage();
#endif
    if (color.a <= 0.0 || coverage <= 0.0) discard;
    vec3 albedo = pow(color.rgb * (color.a / coverage), vec3(2.2));
    guestEmission = vec4(albedo * (0.5 * BLOCKLIGHT_BRIGHTNESS * dot(albedo, vec3(1.0 / 3.0))) * coverage, 1.0);
    guestDisplay = vec4(albedo * coverage, 1.0);
}

/* RENDERTARGETS: 13,14 */
