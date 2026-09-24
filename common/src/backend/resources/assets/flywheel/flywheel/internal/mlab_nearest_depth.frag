// The resolve writes no depth; the nearest occluding node depth it recorded (-1 = none) lands here under GEQUAL:
// a depth-test-off node behind the scene must not push its depth back.

layout(location = 0) out vec4 frag;

uniform sampler2D _flw_mlabNearest;

void main() {
    float depth = texelFetch(_flw_mlabNearest, ivec2(gl_FragCoord.xy), 0).r;
    if (depth < 0.) {
        discard;
    }
    frag = vec4(0.);
    gl_FragDepth = depth;
}
