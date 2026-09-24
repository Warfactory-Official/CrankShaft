// The composite writes no depth; its nearest OIT depth lands here under GEQUAL: a depth-test-off fragment behind the
// scene must not push its depth back. Threshold == oit_composite.frag.

layout(location = 0) out vec4 frag;

uniform sampler2D _flw_accumulate;
uniform sampler2D _flw_depthRange;

void main() {
    if (texelFetch(_flw_accumulate, ivec2(gl_FragCoord.xy), 0).a < 1e-5) {
        discard;
    }
    frag = vec4(0.);
    gl_FragDepth = texelFetch(_flw_depthRange, ivec2(gl_FragCoord.xy), 0).b;
}
