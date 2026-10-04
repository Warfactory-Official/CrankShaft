#version 330 compatibility

in vec2 texCoord;
in vec4 color;
uniform sampler2D texture;
layout(location = 0) out vec4 guestLight;

void main() {
    vec4 emissionColor;
    float coverage;
#ifdef CRANKSHAFT_NATIVE_ADDITIVE
#ifdef CRANKSHAFT_NATIVE_COLOR
    emissionColor = color;
#else
    emissionColor = texture2D(texture, texCoord) * color;
#endif
    coverage = color.a;
#else
    vec2 light;
    float ao;
    vec4 overlay;
    clrwl_computeFragment(texture2D(texture, texCoord), emissionColor, light, ao, overlay);
    coverage = _flw_emissionCoverage();
#endif
    if (emissionColor.a <= 0.0 || coverage <= 0.0) discard;
    guestLight = vec4(pow(emissionColor.rgb * (emissionColor.a / coverage), vec3(2.2)) * coverage, 0.0);
}

/* RENDERTARGETS: 0 */
