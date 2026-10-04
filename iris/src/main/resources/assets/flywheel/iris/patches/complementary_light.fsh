#version 330 compatibility

in vec2 texCoord;
in vec4 glColor;
uniform sampler2D tex;
layout(location = 0) out vec4 guestLight;

void main() {
    vec4 color;
#ifdef CRANKSHAFT_NATIVE_ADDITIVE
#ifdef CRANKSHAFT_NATIVE_COLOR
    color = glColor;
#else
    color = texture(tex, texCoord) * glColor;
#endif
#else
    vec2 light;
    float ao;
    vec4 overlay;
    clrwl_computeFragment(texture(tex, texCoord), color, light, ao, overlay);
#endif
    if (color.a <= 0.0) discard;
    guestLight = vec4(color.rgb * color.a, 0.0);
}

/* RENDERTARGETS: 0 */
