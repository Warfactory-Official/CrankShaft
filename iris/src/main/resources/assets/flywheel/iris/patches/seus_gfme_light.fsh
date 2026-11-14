#version 330 compatibility

#include "/lib/Settings.inc"

in vec4 color;
in vec4 texcoord;
uniform sampler2D texture;
uniform float viewWidth;
uniform float viewHeight;

layout(location = 0) out vec4 guestLight;

void main() {
    // The pack's G-buffers fill the lower-left quadrant.
    if (max(gl_FragCoord.x / viewWidth, gl_FragCoord.y / viewHeight) > 0.5) discard;
    vec4 emissionColor;
    float coverage;
#ifdef CRANKSHAFT_NATIVE_ADDITIVE
#ifdef CRANKSHAFT_NATIVE_COLOR
    emissionColor = color;
#else
    emissionColor = texture2D(texture, texcoord.st) * color;
#endif
    coverage = color.a;
#else
    vec2 light;
    float ao;
    vec4 overlay;
    clrwl_computeFragment(texture2D(texture, texcoord.st), emissionColor, light, ao, overlay);
    coverage = _flw_emissionCoverage();
#endif
    if (emissionColor.a <= 0.0 || coverage <= 0.0) discard;
    // The pack's emissive surface term, albedo * GI_LIGHT_BLOCK_INTENSITY, at emission 1.
    guestLight = vec4(pow(emissionColor.rgb * (emissionColor.a / coverage), vec3(2.2)) * coverage
            * GI_LIGHT_BLOCK_INTENSITY, 0.0);
}

/* RENDERTARGETS: 17 */
