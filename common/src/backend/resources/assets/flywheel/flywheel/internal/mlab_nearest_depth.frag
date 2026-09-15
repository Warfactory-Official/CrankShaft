// MlabResolveVariant.ADDITIVE depth: the colour resolve writes no depth (none exists for additive-only pixels); the
// nearest occluding node depth it recorded (-1 = none) lands here.

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
