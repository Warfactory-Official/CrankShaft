#version 330

layout(std140) uniform LodFadeMask {
    mat4 InverseViewProjection;
    mat4 InverseProjection;
    int ZeroToOne;
    float VanillaRadius;
    int TerrainCaptured;
    int InstancesCovered;
};

uniform sampler2D DepthSampler;
// Main depth after opaque terrain, and around the translucent terrain draw.
uniform sampler2D TerrainSampler;
uniform sampler2D BeforeTranslucentSampler;
uniform sampler2D AfterTranslucentSampler;
// OIT instances this frame: .r = minus the nearest linear depth, -far where none drew.
uniform sampler2D InstanceSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec4 ndc = vec4(texCoord * 2.0 - 1.0, ZeroToOne != 0 ? depth : depth * 2.0 - 1.0, 1.0);
    vec4 unprojected = InverseViewProjection * ndc;
    bool terrain = depth != 0.0 && length(unprojected.xz / unprojected.w) < VanillaRadius;
    if (TerrainCaptured != 0) {
        float before = texture(BeforeTranslucentSampler, texCoord).r, after = texture(AfterTranslucentSampler, texCoord).r;
        terrain = terrain && depth == (after != before ? after : texture(TerrainSampler, texCoord).r);
    }
    if (terrain && InstancesCovered != 0) {
        vec4 view = InverseProjection * ndc;
        // The OIT depth writeback may already put the instance's own depth here, which the coverage repeats only to
        // rounding: the surface stays terrain only with the nearest instance clearly behind it.
        terrain = -texture(InstanceSampler, texCoord).r > -view.z / view.w * 1.001;
    }
    fragColor = vec4(terrain ? depth : 0.0);
}
