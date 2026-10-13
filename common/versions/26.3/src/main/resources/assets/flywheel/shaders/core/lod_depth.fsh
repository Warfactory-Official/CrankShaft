#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform LodDepth {
    mat4 LodInverseViewProjection;
    mat4 ViewProjection;
    float LodFarDepth;
    int LodZeroToOne;
    int ZeroToOne;
    float VanillaRadius;
};

uniform sampler2D LodDepthSampler;

layout(location = 0) in vec2 texCoord;

void main() {
    float lodDepth = texture(LodDepthSampler, texCoord).r;
    if (lodDepth == LodFarDepth) {
        discard;
    }
    vec4 ndc = vec4(texCoord * 2.0 - 1.0, LodZeroToOne != 0 ? lodDepth : lodDepth * 2.0 - 1.0, 1.0);
    vec4 unprojected = LodInverseViewProjection * ndc;
    vec3 relative = unprojected.xyz / unprojected.w;
    float horizontal = length(relative.xz);
    if (horizontal < VanillaRadius) {
        relative *= VanillaRadius / max(horizontal, 1e-4);
    }
    vec4 clip = ViewProjection * vec4(relative, 1.0);
    float depth = clip.z / clip.w;
    gl_FragDepth = clamp(ZeroToOne != 0 ? depth : depth * 0.5 + 0.5, 0.0, 1.0);
}
