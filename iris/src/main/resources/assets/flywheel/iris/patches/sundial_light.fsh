#version 330 compatibility

#include "/settings/GlobalSettings.glsl"

in vec4 texlmcoord;

uniform sampler2D gtexture;

layout(location = 0) out vec4 guestLight;

void main() {
    vec4 color;
    vec2 light;
    float ao;
    vec4 overlay;
    clrwl_computeFragment(texture(gtexture, texlmcoord.st), color, light, ao, overlay);
    guestLight = vec4(pow(color.rgb, vec3(2.2)) * color.a * BLOCK_LIGHT_BRIGHTNESS * 3.14159265, 1.0);
}

/* RENDERTARGETS: 15 */
