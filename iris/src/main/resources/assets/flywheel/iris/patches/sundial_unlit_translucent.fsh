#version 430 compatibility

#include "/settings/GlobalSettings.glsl"

in vec4 texlmcoord;
uniform sampler2D gtexture;
layout(location = 0) out vec4 guestUnlit;

void main() {
    vec4 color;
    vec2 light;
    float ao;
    vec4 overlay;
    clrwl_computeFragment(texture(gtexture, texlmcoord.st), color, light, ao, overlay);
    if (color.a <= 0.0) discard;
    color.rgb = mix(color.rgb, overlay.rgb, overlay.a) * _flw_unlitCardinalFactor();
    guestUnlit = vec4(pow(color.rgb, vec3(2.2)), color.a);
}

/* RENDERTARGETS: 14 */
