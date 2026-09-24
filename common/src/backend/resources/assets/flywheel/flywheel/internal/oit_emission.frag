// ORDER_INDEPENDENT_ADDITIVE emission over pixels the composite discarded (no occluding layer). Threshold ==
// oit_composite.frag.

layout(location = 0) out vec4 frag;

uniform sampler2D _flw_accumulate;
uniform sampler2D _flw_emission;

void main() {
    if (texelFetch(_flw_accumulate, ivec2(gl_FragCoord.xy), 0).a >= 1e-5) {
        discard;
    }
    vec3 emission = texelFetch(_flw_emission, ivec2(gl_FragCoord.xy), 0).rgb;
    if (max(max(emission.r, emission.g), emission.b) <= 0.) {
        discard;
    }
    frag = vec4(emission, 0.);
}
