
uniform sampler2D farColor;
uniform sampler2D farDepth;
uniform mat4 farInverseProjection;
uniform mat4 viewToHostClip;
uniform vec2 hostSize;
uniform bool hostZeroToOne;

out vec4 color;

void main() {
    vec2 uv = gl_FragCoord.xy / hostSize;
    float depth = texture(farDepth, uv).r;
    // Reversed-Z, cleared to 0.
    if (depth == 0.0) {
        discard;
    }
    vec4 view = farInverseProjection * vec4(uv * 2.0 - 1.0, depth, 1.0);
    vec4 clip = viewToHostClip * vec4(view.xyz / view.w, 1.0);
    float ndc = clip.z / clip.w;
    gl_FragDepth = clamp(hostZeroToOne ? ndc : ndc * 0.5 + 0.5, 0.0, 1.0);
    color = vec4(texture(farColor, uv).rgb, 1.0);
}
