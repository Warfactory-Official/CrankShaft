#version 430 compatibility

#include "/Lib/Settings.glsl"

in vec2 v_texCoord;

uniform sampler2D tex;

layout(location = 0) out vec4 guestEmission;
layout(location = 1) out vec4 guestDisplay;

void main() {
    vec4 color;
    vec2 light;
    float ao;
    vec4 overlay;
    clrwl_computeFragment(texture(tex, v_texCoord), color, light, ao, overlay);
    vec3 albedo = pow(color.rgb, vec3(2.2)) * color.a;
    guestEmission = vec4(albedo * (0.5 * BLOCKLIGHT_BRIGHTNESS * dot(albedo, vec3(1.0 / 3.0))), 1.0);
    guestDisplay = vec4(albedo, 1.0);
}

/* RENDERTARGETS: 13,14 */
