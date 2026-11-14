layout(location = 0) out vec4 frag;

uniform sampler2D _flw_instanceBounds;

void main() {
    vec2 bounds = texelFetch(_flw_instanceBounds, ivec2(gl_FragCoord.xy), 0).rg;
    if (bounds.g < 0.) {
        discard;
    }
    frag = vec4(bounds, _flw_nearestDeviceDepth(bounds.r), 0.);
}
