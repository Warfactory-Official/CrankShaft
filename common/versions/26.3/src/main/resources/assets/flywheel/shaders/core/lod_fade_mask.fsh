#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform LodFadeMask {
    mat4 InverseViewProjection;
    int ZeroToOne;
    float VanillaRadius;
    int TerrainCaptured;
};

uniform sampler2D DepthSampler;
// Main depth after opaque terrain, and around the translucent terrain draw.
uniform sampler2D TerrainSampler;
uniform sampler2D BeforeTranslucentSampler;
uniform sampler2D AfterTranslucentSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec4 unprojected = InverseViewProjection
            * vec4(texCoord * 2.0 - 1.0, ZeroToOne != 0 ? depth : depth * 2.0 - 1.0, 1.0);
    bool terrain = depth != 0.0 && length(unprojected.xz / unprojected.w) < VanillaRadius;
    if (TerrainCaptured != 0) {
        float before = texture(BeforeTranslucentSampler, texCoord).r, after = texture(AfterTranslucentSampler, texCoord).r;
        terrain = terrain && depth == (after != before ? after : texture(TerrainSampler, texCoord).r);
    }
    fragColor = vec4(terrain ? depth : 0.0);
}
