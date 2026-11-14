
uniform sampler2D farColor;
uniform sampler2D farDepth;
// The main depth as vanilla left it: zero-to-one reversed-Z, 0 where nothing drew.
uniform sampler2D mainDepth;
uniform mat4 farInverseProjection;
uniform mat4 viewToMainClip;
// Camera relative.
uniform mat4 mainInverseViewProjection;
uniform vec2 mainSize;
uniform float vanillaRadius;

out vec4 color;

// Far pixels where vanilla drew within its radius, re-projected into the main depth that tests them nearer. Elsewhere
// the host composited them; past the radius main depth may hold LoD depth.
void main() {
    vec2 uv = gl_FragCoord.xy / mainSize;
    float depth = texture(farDepth, uv).r;
    float vanilla = texture(mainDepth, uv).r;
    vec4 surface = mainInverseViewProjection * vec4(uv * 2.0 - 1.0, vanilla, 1.0);
    if (depth == 0.0 || vanilla == 0.0 || length(surface.xz / surface.w) >= vanillaRadius) {
        discard;
    }
    vec4 view = farInverseProjection * vec4(uv * 2.0 - 1.0, depth, 1.0);
    vec4 clip = viewToMainClip * vec4(view.xyz / view.w, 1.0);
    gl_FragDepth = clamp(clip.z / clip.w, 0.0, 1.0);
    color = vec4(texture(farColor, uv).rgb, 1.0);
}
