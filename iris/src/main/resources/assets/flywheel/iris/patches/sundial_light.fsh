#version 330 compatibility

#include "/settings/GlobalSettings.glsl"

in vec4 texlmcoord;
#ifdef CRANKSHAFT_NATIVE_ADDITIVE
in vec4 color;
#endif

uniform sampler2D gtexture;

layout(location = 0) out vec4 guestLight;

void main() {
    vec4 emissionColor;
    vec2 light;
    float ao;
    vec4 overlay;
    float coverage;
#ifdef CRANKSHAFT_NATIVE_ADDITIVE
#ifdef CRANKSHAFT_NATIVE_COLOR
    emissionColor = color;
#else
    emissionColor = texture(gtexture, texlmcoord.st) * color;
#endif
    coverage = color.a;
#else
    clrwl_computeFragment(texture(gtexture, texlmcoord.st), emissionColor, light, ao, overlay);
    coverage = _flw_emissionCoverage();
#endif
    if (emissionColor.a <= 0.0 || coverage <= 0.0) discard;
    guestLight = vec4(pow(emissionColor.rgb * (emissionColor.a / coverage), vec3(2.2)) * BLOCK_LIGHT_BRIGHTNESS * 3.14159265 * coverage, 1.0);
}

/* RENDERTARGETS: 15 */
