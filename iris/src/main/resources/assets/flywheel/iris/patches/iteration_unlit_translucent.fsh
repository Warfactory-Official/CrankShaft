#version 430 compatibility

#include "/Lib/Settings.glsl"

in vec2 v_texCoord;
uniform sampler2D tex;
uniform sampler2D pixelData2D;
layout(location = 0) out vec4 guestUnlit;

void main() {
    vec4 color;
    vec2 light;
    float ao;
    vec4 overlay;
    clrwl_computeFragment(texture(tex, v_texCoord), color, light, ao, overlay);
    color.rgb = mix(color.rgb, overlay.rgb, overlay.a) * _flw_unlitCardinalFactor();
    vec3 albedo = pow(color.rgb, vec3(2.2));
    float displayScale = texelFetch(pixelData2D, ivec2(PIXELDATA_EXPOSURE, 0), 0).x * 0.13;
    float emissionScale = 0.2 * BLOCKLIGHT_BRIGHTNESS * dot(albedo, vec3(1.0 / 3.0));
    guestUnlit = vec4(albedo * (emissionScale + displayScale), color.a);
}

/* RENDERTARGETS: 18 */
