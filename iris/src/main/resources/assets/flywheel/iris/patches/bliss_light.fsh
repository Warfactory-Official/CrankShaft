#version 330 compatibility

in vec4 color;
in vec2 texcoord;
flat in float exposure;
uniform sampler2D texture;

layout(location = 0) out vec4 guestLight;

void main() {
    vec4 emissionColor;
    float coverage;
#ifdef CRANKSHAFT_NATIVE_ADDITIVE
#ifdef CRANKSHAFT_NATIVE_COLOR
    emissionColor = color;
#else
    emissionColor = texture2D(texture, texcoord) * color;
#endif
    coverage = color.a;
#else
    vec2 light;
    float ao;
    vec4 overlay;
    clrwl_computeFragment(texture2D(texture, texcoord), emissionColor, light, ao, overlay);
    coverage = _flw_emissionCoverage();
#endif
    if (emissionColor.a <= 0.0 || coverage <= 0.0) discard;
    // The pack's beacon-beam emission scale, in its composite colour units.
    float brightness = mix(10.0, 100.0, clamp(exp(-10.0 * exposure), 0.0, 1.0));
    guestLight = vec4(pow(emissionColor.rgb * (emissionColor.a / coverage), vec3(2.2)) * coverage * brightness, 0.0);
}

/* RENDERTARGETS: 17 */
