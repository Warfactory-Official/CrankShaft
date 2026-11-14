#version 430 compatibility

#include "/Lib/Settings.glsl"

in vec2 v_texCoord;
uniform sampler2D tex;
layout(location = 0) out vec4 guestUnlit;

void main() {
    vec4 color;
    vec2 light;
    float ao;
    vec4 overlay;
    clrwl_computeFragment(texture(tex, v_texCoord), color, light, ao, overlay);
    color.rgb = mix(color.rgb, overlay.rgb, overlay.a) * _flw_unlitCardinalFactor();
    guestUnlit = color;
}

/* RENDERTARGETS: 18 */
